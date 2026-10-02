package com.pitchcode.nextmove.billing;

import android.app.Activity;
import android.content.Context;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import com.pitchcode.nextmove.data.PlanState;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps Google Play Billing for a single one-time (lifetime) Premium purchase.
 *
 * The in-app product must be created in the Play Console with the ID below, and
 * the app must be distributed through Google Play for a real purchase dialog to
 * appear. In a sideloaded or unpublished build the client connects but reports
 * the product as unavailable, which {@link Listener} surfaces to the UI.
 */
public final class BillingManager {
    public static final String PREMIUM = "nextmove_premium_lifetime";

    public interface Listener {
        void onBillingState(String state);
        void onPremiumChanged();
    }

    private final Context appContext;
    private final Listener listener;
    private final Map<String, ProductDetails> products = new HashMap<>();
    private BillingClient client;
    private boolean connected;

    public BillingManager(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    private final PurchasesUpdatedListener purchasesUpdated = (result, purchases) -> {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) handlePurchase(purchase);
        } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            notifyState("cancelled");
        } else {
            notifyState("error");
        }
    };

    public void start() {
        if (client != null) return;
        try {
            client = BillingClient.newBuilder(appContext)
                    .setListener(purchasesUpdated)
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                            .enableOneTimeProducts().build())
                    .build();
            client.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(BillingResult result) {
                    connected = result.getResponseCode() == BillingClient.BillingResponseCode.OK;
                    if (connected) {
                        queryProducts();
                        queryExistingPurchases();
                    } else {
                        notifyState("unavailable");
                    }
                }

                @Override
                public void onBillingServiceDisconnected() {
                    connected = false;
                }
            });
        } catch (RuntimeException error) {
            notifyState("unavailable");
        }
    }

    private void queryProducts() {
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PREMIUM)
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        client.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder()
                        .setProductList(Collections.singletonList(product)).build(),
                (result, details) -> {
                    products.clear();
                    if (details != null) {
                        for (ProductDetails detail : details) {
                            products.put(detail.getProductId(), detail);
                        }
                    }
                    notifyState(products.isEmpty() ? "unavailable" : "ready");
                });
    }

    /** Launches the Play purchase flow for the one-time product. */
    public boolean launch(Activity activity, String productId) {
        if (client == null || !connected) {
            start();
            notifyState("unavailable");
            return false;
        }
        ProductDetails details = products.get(productId);
        if (details == null || details.getOneTimePurchaseOfferDetails() == null) {
            notifyState("unavailable");
            return false;
        }
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(details)
                                .build()))
                .build();
        client.launchBillingFlow(activity, params);
        return true;
    }

    private void queryExistingPurchases() {
        client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP).build(),
                (result, purchases) -> {
                    if (purchases != null) {
                        for (Purchase purchase : purchases) handlePurchase(purchase);
                    }
                });
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return;
        PlanState.setPremium(appContext, true);
        if (!purchase.isAcknowledged()) {
            client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.getPurchaseToken()).build(),
                    result -> { });
        }
        notifyState("purchased");
        if (listener != null) listener.onPremiumChanged();
    }

    public void end() {
        if (client != null) {
            try {
                client.endConnection();
            } catch (RuntimeException ignored) {
                // Client may already be torn down.
            }
            client = null;
            connected = false;
        }
    }

    private void notifyState(String state) {
        if (listener != null) listener.onBillingState(state);
    }
}

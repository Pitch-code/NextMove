package com.pitchcode.nextmove.data;

import com.pitchcode.nextmove.R;

public final class SampleAnalysis {
    public enum Kind { BILL, VISIT, RETURN, SCAM }

    public final Kind kind;
    public final int chip;
    public final int title;
    public final int summary;
    public final int primaryLabel;
    public final int primaryValue;
    public final int date;
    public final int action;
    public final int evidence;
    public final int calendarTitle;
    public final boolean danger;

    private SampleAnalysis(
            Kind kind,
            int chip,
            int title,
            int summary,
            int primaryLabel,
            int primaryValue,
            int date,
            int action,
            int evidence,
            int calendarTitle,
            boolean danger) {
        this.kind = kind;
        this.chip = chip;
        this.title = title;
        this.summary = summary;
        this.primaryLabel = primaryLabel;
        this.primaryValue = primaryValue;
        this.date = date;
        this.action = action;
        this.evidence = evidence;
        this.calendarTitle = calendarTitle;
        this.danger = danger;
    }

    public static SampleAnalysis of(Kind kind) {
        return switch (kind) {
            case BILL -> new SampleAnalysis(
                    kind, R.string.sample_bill, R.string.bill_title, R.string.bill_summary,
                    R.string.amount_label, R.string.bill_amount, R.string.bill_date,
                    R.string.bill_action, R.string.bill_evidence, R.string.calendar_bill_title, false);
            case VISIT -> new SampleAnalysis(
                    kind, R.string.sample_visit, R.string.visit_title, R.string.visit_summary,
                    R.string.time_label, R.string.visit_amount, R.string.visit_date,
                    R.string.visit_action, R.string.visit_evidence, R.string.calendar_visit_title, false);
            case RETURN -> new SampleAnalysis(
                    kind, R.string.sample_return, R.string.return_title, R.string.return_summary,
                    R.string.amount_label, R.string.return_amount, R.string.return_date,
                    R.string.return_action, R.string.return_evidence, R.string.calendar_return_title, false);
            case SCAM -> new SampleAnalysis(
                    kind, R.string.sample_scam, R.string.scam_title, R.string.scam_summary,
                    R.string.risk_label, R.string.risk_high, R.string.scam_date,
                    R.string.scam_action, R.string.scam_evidence, R.string.calendar_scam_title, true);
        };
    }
}

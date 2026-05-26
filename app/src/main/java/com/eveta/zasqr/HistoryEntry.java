package com.eveta.zasqr;

class HistoryEntry {
    String  topupId;
    String  amount;
    String  reference;
    String  concept;
    String  qrPayload;
    long    requestAtMs;  // when backend sent the topup
    long    timestampMs;  // when we submitted the QR payload
    boolean submitted;
    boolean verified;
}

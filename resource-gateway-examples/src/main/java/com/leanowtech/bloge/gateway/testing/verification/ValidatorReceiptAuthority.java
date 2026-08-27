package com.leanowtech.bloge.gateway.testing.verification;

/** Trust boundary for independently issued validator rejection receipts. */
public interface ValidatorReceiptAuthority {
    String authorityId();

    Verification verify(ValidatorAdversarialCase expected, ValidatorRejectionReceipt receipt);

    enum Status { VERIFIED, REJECTED }

    record Verification(Status status, String diagnosticCode) {
        public Verification {
            if (status == null) {
                throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.INVALID_INPUT);
            }
            diagnosticCode = ValidatorVerificationSupport.token(diagnosticCode);
        }

        public static Verification verified() {
            return new Verification(Status.VERIFIED, "receipt-authority.verified");
        }

        public static Verification rejected() {
            return new Verification(Status.REJECTED, "receipt-authority.rejected");
        }
    }
}

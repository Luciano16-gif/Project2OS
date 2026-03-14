package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.process.ResultStatus;

public final class OperationApplyResult {

    private final ResultStatus resultStatus;
    private final String errorMessage;

    public OperationApplyResult(ResultStatus resultStatus, String errorMessage) {
        if (resultStatus == null) {
            throw new IllegalArgumentException("resultStatus cannot be null");
        }
        this.resultStatus = resultStatus;
        this.errorMessage = normalizeOptional(errorMessage);
    }

    public static OperationApplyResult success() {
        return new OperationApplyResult(ResultStatus.SUCCESS, null);
    }

    public static OperationApplyResult failed(String errorMessage) {
        return new OperationApplyResult(ResultStatus.FAILED, errorMessage);
    }

    public static OperationApplyResult cancelled(String errorMessage) {
        return new OperationApplyResult(ResultStatus.CANCELLED, errorMessage);
    }

    public ResultStatus getResultStatus() {
        return resultStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

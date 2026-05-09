package ve.edu.unimet.so.project2.coordinator.channel;

public record DiskServiceResult(
        String processId,
        String requestId,
        int previousHeadBlock,
        int newHeadBlock,
        int traveledDistance,
        String serviceThreadName) {

    public DiskServiceResult {
        processId = requireNonBlank(processId, "processId");
        requestId = requireNonBlank(requestId, "requestId");
        serviceThreadName = requireNonBlank(serviceThreadName, "serviceThreadName");
        if (previousHeadBlock < 0) {
            throw new IllegalArgumentException("previousHeadBlock cannot be negative");
        }
        if (newHeadBlock < 0) {
            throw new IllegalArgumentException("newHeadBlock cannot be negative");
        }
        if (traveledDistance < 0) {
            throw new IllegalArgumentException("traveledDistance cannot be negative");
        }
    }

    public String getProcessId() {
        return processId;
    }

    public String getRequestId() {
        return requestId;
    }

    public int getPreviousHeadBlock() {
        return previousHeadBlock;
    }

    public int getNewHeadBlock() {
        return newHeadBlock;
    }

    public int getTraveledDistance() {
        return traveledDistance;
    }

    public String getServiceThreadName() {
        return serviceThreadName;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}

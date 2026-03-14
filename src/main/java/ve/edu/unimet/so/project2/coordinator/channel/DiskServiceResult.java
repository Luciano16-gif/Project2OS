package ve.edu.unimet.so.project2.coordinator.channel;

public final class DiskServiceResult {

    private final String processId;
    private final String requestId;
    private final int previousHeadBlock;
    private final int newHeadBlock;
    private final int traveledDistance;
    private final String serviceThreadName;

    public DiskServiceResult(
            String processId,
            String requestId,
            int previousHeadBlock,
            int newHeadBlock,
            int traveledDistance,
            String serviceThreadName) {
        this.processId = requireNonBlank(processId, "processId");
        this.requestId = requireNonBlank(requestId, "requestId");
        if (previousHeadBlock < 0) {
            throw new IllegalArgumentException("previousHeadBlock cannot be negative");
        }
        if (newHeadBlock < 0) {
            throw new IllegalArgumentException("newHeadBlock cannot be negative");
        }
        if (traveledDistance < 0) {
            throw new IllegalArgumentException("traveledDistance cannot be negative");
        }
        this.previousHeadBlock = previousHeadBlock;
        this.newHeadBlock = newHeadBlock;
        this.traveledDistance = traveledDistance;
        this.serviceThreadName = requireNonBlank(serviceThreadName, "serviceThreadName");
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

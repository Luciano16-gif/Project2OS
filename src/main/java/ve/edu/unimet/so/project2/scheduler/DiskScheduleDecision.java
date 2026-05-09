package ve.edu.unimet.so.project2.scheduler;

import ve.edu.unimet.so.project2.disk.DiskHeadDirection;

public record DiskScheduleDecision(
        String selectedProcessId,
        String selectedRequestId,
        int previousHeadBlock,
        int newHeadBlock,
        int traveledDistance,
        DiskHeadDirection resultingDirection) {

    public DiskScheduleDecision {
        selectedProcessId = requireNonBlank(selectedProcessId, "selectedProcessId");
        selectedRequestId = requireNonBlank(selectedRequestId, "selectedRequestId");
        if (previousHeadBlock < 0) {
            throw new IllegalArgumentException("previousHeadBlock cannot be negative");
        }
        if (newHeadBlock < 0) {
            throw new IllegalArgumentException("newHeadBlock cannot be negative");
        }
        if (traveledDistance < 0) {
            throw new IllegalArgumentException("traveledDistance cannot be negative");
        }
        if (resultingDirection == null) {
            throw new IllegalArgumentException("resultingDirection cannot be null");
        }
    }

    public String getSelectedProcessId() { return selectedProcessId; }
    public String getSelectedRequestId() { return selectedRequestId; }
    public int getPreviousHeadBlock() { return previousHeadBlock; }
    public int getNewHeadBlock() { return newHeadBlock; }
    public int getTraveledDistance() { return traveledDistance; }
    public DiskHeadDirection getResultingDirection() { return resultingDirection; }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}

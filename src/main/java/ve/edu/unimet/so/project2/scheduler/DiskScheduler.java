package ve.edu.unimet.so.project2.scheduler;

import ve.edu.unimet.so.project2.disk.DiskHead;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.process.ProcessControlBlock;

public final class DiskScheduler {

    public DiskScheduleDecision selectNext(
            DiskSchedulingPolicy policy,
            DiskHead headSnapshot,
            ProcessControlBlock[] readySnapshot,
            int totalBlocks) {
        if (policy == null) {
            throw new IllegalArgumentException("policy cannot be null");
        }
        if (headSnapshot == null) {
            throw new IllegalArgumentException("headSnapshot cannot be null");
        }
        if (readySnapshot == null) {
            throw new IllegalArgumentException("readySnapshot cannot be null");
        }
        if (totalBlocks <= 0) {
            throw new IllegalArgumentException("totalBlocks must be greater than zero");
        }

        ProcessControlBlock[] candidates = collectNonNullCandidates(readySnapshot);
        if (candidates.length == 0) {
            return null;
        }

        return switch (policy) {
            case FIFO -> selectFifo(headSnapshot, candidates);
            case SSTF -> selectSstf(headSnapshot, candidates);
            case SCAN -> selectScan(headSnapshot, candidates, totalBlocks);
            case C_SCAN -> selectCScan(headSnapshot, candidates, totalBlocks);
        };
    }

    private DiskScheduleDecision selectFifo(DiskHead headSnapshot, ProcessControlBlock[] candidates) {
        ProcessControlBlock selected = candidates[0];
        for (int i = 1; i < candidates.length; i++) {
            if (compareArrival(candidates[i], selected) < 0) {
                selected = candidates[i];
            }
        }
        return buildDirectDecision(headSnapshot, selected, headSnapshot.getDirection());
    }

    private DiskScheduleDecision selectSstf(DiskHead headSnapshot, ProcessControlBlock[] candidates) {
        ProcessControlBlock selected = candidates[0];
        int selectedDistance = distance(headSnapshot.getCurrentBlock(), selected.getTargetBlock());

        for (int i = 1; i < candidates.length; i++) {
            ProcessControlBlock candidate = candidates[i];
            int candidateDistance = distance(headSnapshot.getCurrentBlock(), candidate.getTargetBlock());
            if (candidateDistance < selectedDistance
                    || (candidateDistance == selectedDistance && compareArrival(candidate, selected) < 0)) {
                selected = candidate;
                selectedDistance = candidateDistance;
            }
        }

        return buildDirectDecision(headSnapshot, selected, headSnapshot.getDirection());
    }

    private DiskScheduleDecision selectScan(DiskHead headSnapshot, ProcessControlBlock[] candidates, int totalBlocks) {
        ProcessControlBlock inDirection = findNearestInDirection(
                candidates,
                headSnapshot.getCurrentBlock(),
                headSnapshot.getDirection());
        if (inDirection != null) {
            return buildDirectDecision(headSnapshot, inDirection, headSnapshot.getDirection());
        }

        DiskHeadDirection reversedDirection = reverse(headSnapshot.getDirection());
        ProcessControlBlock oppositeDirection = findNearestInDirection(
                candidates,
                headSnapshot.getCurrentBlock(),
                reversedDirection);
        int edge = edgeFor(headSnapshot.getDirection(), totalBlocks);
        int traveledDistance = distance(headSnapshot.getCurrentBlock(), edge)
                + distance(edge, oppositeDirection.getTargetBlock());
        return new DiskScheduleDecision(
                oppositeDirection.getProcessId(),
                oppositeDirection.getRequest().getRequestId(),
                headSnapshot.getCurrentBlock(),
                oppositeDirection.getTargetBlock(),
                traveledDistance,
                reversedDirection);
    }

    private DiskScheduleDecision selectCScan(DiskHead headSnapshot, ProcessControlBlock[] candidates, int totalBlocks) {
        ProcessControlBlock inDirection = findNearestInDirection(
                candidates,
                headSnapshot.getCurrentBlock(),
                headSnapshot.getDirection());
        if (inDirection != null) {
            return buildDirectDecision(headSnapshot, inDirection, headSnapshot.getDirection());
        }

        DiskHeadDirection direction = headSnapshot.getDirection();
        ProcessControlBlock wrappedCandidate = findWrappedCandidate(candidates, direction);
        int edge = edgeFor(direction, totalBlocks);
        int wrapEdge = wrapEdgeFor(direction, totalBlocks);
        int traveledDistance = distance(headSnapshot.getCurrentBlock(), edge)
                + distance(edge, wrapEdge)
                + distance(wrapEdge, wrappedCandidate.getTargetBlock());
        return new DiskScheduleDecision(
                wrappedCandidate.getProcessId(),
                wrappedCandidate.getRequest().getRequestId(),
                headSnapshot.getCurrentBlock(),
                wrappedCandidate.getTargetBlock(),
                traveledDistance,
                direction);
    }

    private DiskScheduleDecision buildDirectDecision(
            DiskHead headSnapshot,
            ProcessControlBlock selected,
            DiskHeadDirection resultingDirection) {
        return new DiskScheduleDecision(
                selected.getProcessId(),
                selected.getRequest().getRequestId(),
                headSnapshot.getCurrentBlock(),
                selected.getTargetBlock(),
                distance(headSnapshot.getCurrentBlock(), selected.getTargetBlock()),
                resultingDirection);
    }

    private ProcessControlBlock findNearestInDirection(
            ProcessControlBlock[] candidates,
            int currentBlock,
            DiskHeadDirection direction) {
        ProcessControlBlock selected = null;
        int selectedDistance = Integer.MAX_VALUE;

        for (ProcessControlBlock candidate : candidates) {
            if (!isInDirection(currentBlock, candidate.getTargetBlock(), direction)) {
                continue;
            }
            int candidateDistance = distance(currentBlock, candidate.getTargetBlock());
            if (selected == null
                    || candidateDistance < selectedDistance
                    || (candidateDistance == selectedDistance && compareArrival(candidate, selected) < 0)) {
                selected = candidate;
                selectedDistance = candidateDistance;
            }
        }

        return selected;
    }

    private ProcessControlBlock findWrappedCandidate(ProcessControlBlock[] candidates, DiskHeadDirection direction) {
        ProcessControlBlock selected = null;

        for (ProcessControlBlock candidate : candidates) {
            if (selected == null) {
                selected = candidate;
                continue;
            }

            if (direction == DiskHeadDirection.UP) {
                if (candidate.getTargetBlock() < selected.getTargetBlock()
                        || (candidate.getTargetBlock() == selected.getTargetBlock()
                        && compareArrival(candidate, selected) < 0)) {
                    selected = candidate;
                }
            } else if (candidate.getTargetBlock() > selected.getTargetBlock()
                    || (candidate.getTargetBlock() == selected.getTargetBlock()
                    && compareArrival(candidate, selected) < 0)) {
                selected = candidate;
            }
        }

        return selected;
    }

    private ProcessControlBlock[] collectNonNullCandidates(ProcessControlBlock[] readySnapshot) {
        int count = 0;
        for (ProcessControlBlock process : readySnapshot) {
            if (process != null) {
                count++;
            }
        }

        ProcessControlBlock[] candidates = new ProcessControlBlock[count];
        int index = 0;
        for (ProcessControlBlock process : readySnapshot) {
            if (process != null) {
                candidates[index++] = process;
            }
        }
        return candidates;
    }

    private boolean isInDirection(int currentBlock, int targetBlock, DiskHeadDirection direction) {
        if (direction == DiskHeadDirection.UP) {
            return targetBlock >= currentBlock;
        }
        return targetBlock <= currentBlock;
    }

    private int compareArrival(ProcessControlBlock left, ProcessControlBlock right) {
        long leftArrival = left.getRequest().getArrivalOrder();
        long rightArrival = right.getRequest().getArrivalOrder();
        if (leftArrival < rightArrival) {
            return -1;
        }
        if (leftArrival > rightArrival) {
            return 1;
        }
        return left.getRequest().getRequestId().compareTo(right.getRequest().getRequestId());
    }

    private int distance(int from, int to) {
        return Math.abs(from - to);
    }

    private int edgeFor(DiskHeadDirection direction, int totalBlocks) {
        return direction == DiskHeadDirection.UP ? totalBlocks - 1 : 0;
    }

    private int wrapEdgeFor(DiskHeadDirection direction, int totalBlocks) {
        return direction == DiskHeadDirection.UP ? 0 : totalBlocks - 1;
    }

    private DiskHeadDirection reverse(DiskHeadDirection direction) {
        return direction == DiskHeadDirection.UP ? DiskHeadDirection.DOWN : DiskHeadDirection.UP;
    }
}

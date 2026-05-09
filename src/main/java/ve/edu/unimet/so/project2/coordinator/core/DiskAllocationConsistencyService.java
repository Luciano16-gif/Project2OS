package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.disk.DiskBlock;
import ve.edu.unimet.so.project2.disk.DiskHead;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FsNode;

final class DiskAllocationConsistencyService {

    SimulatedDisk copyOf(SimulatedDisk sourceDisk) {
        requireDisk(sourceDisk);
        DiskHead sourceHead = sourceDisk.getHead();
        SimulatedDisk copy = new SimulatedDisk(
                sourceDisk.getTotalBlocks(),
                sourceHead.getCurrentBlock(),
                sourceHead.getDirection());
        for (int blockIndex = 0; blockIndex < sourceDisk.getTotalBlocks(); blockIndex++) {
            DiskBlock sourceBlock = sourceDisk.getBlock(blockIndex);
            if (!sourceBlock.isFree()) {
                copy.allocateBlock(
                        blockIndex,
                        sourceBlock.getOwnerFileId(),
                        sourceBlock.getNextBlockIndex(),
                        sourceBlock.isSystemReserved());
            }
        }
        return copy;
    }

    void synchronizeDiskWithCatalog(
            SimulationApplicationState applicationState,
            SimulatedDisk targetDisk) {
        FileNode[] files = getFileSnapshot(applicationState);
        boolean[] claimedBlocks = new boolean[targetDisk.getTotalBlocks()];
        boolean[] reservedFirstBlocks = buildReservedFirstBlocks(files, targetDisk);
        for (FileNode file : files) {
            synchronizeFileAllocation(file, claimedBlocks, reservedFirstBlocks, targetDisk);
        }
        ensureNoOrphanedOccupiedBlocks(claimedBlocks, targetDisk);
    }

    void validateDiskMatchesCatalog(
            SimulationApplicationState applicationState,
            SimulatedDisk targetDisk) {
        FileNode[] files = getFileSnapshot(applicationState);
        boolean[] claimedBlocks = new boolean[targetDisk.getTotalBlocks()];
        buildReservedFirstBlocks(files, targetDisk);
        for (FileNode file : files) {
            validateExistingFileAllocation(file, claimedBlocks, targetDisk);
        }
        ensureNoOrphanedOccupiedBlocks(claimedBlocks, targetDisk);
    }

    private FileNode[] getFileSnapshot(SimulationApplicationState applicationState) {
        requireApplicationState(applicationState);
        FsNode[] nodes = applicationState.getFileSystemCatalog().getAllNodesSnapshot();
        int fileCount = 0;
        for (FsNode node : nodes) {
            if (node instanceof FileNode) {
                fileCount++;
            }
        }

        FileNode[] files = new FileNode[fileCount];
        int index = 0;
        for (FsNode node : nodes) {
            if (node instanceof FileNode file) {
                files[index++] = file;
            }
        }
        return files;
    }

    private boolean[] buildReservedFirstBlocks(FileNode[] files, SimulatedDisk targetDisk) {
        boolean[] reservedFirstBlocks = new boolean[targetDisk.getTotalBlocks()];
        for (FileNode file : files) {
            int firstBlockIndex = file.getFirstBlockIndex();
            if (!targetDisk.isValidIndex(firstBlockIndex)) {
                throw new IllegalArgumentException("file firstBlockIndex is out of disk range: " + file.getId());
            }
            if (reservedFirstBlocks[firstBlockIndex]) {
                throw new IllegalArgumentException("duplicate file firstBlockIndex detected: " + firstBlockIndex);
            }
            reservedFirstBlocks[firstBlockIndex] = true;
        }
        return reservedFirstBlocks;
    }

    private void synchronizeFileAllocation(
            FileNode file,
            boolean[] claimedBlocks,
            boolean[] reservedFirstBlocks,
            SimulatedDisk targetDisk) {
        int firstBlockIndex = file.getFirstBlockIndex();
        DiskBlock firstBlock = targetDisk.getBlock(firstBlockIndex);
        if (firstBlock.isFree()) {
            hydrateFileAllocation(file, claimedBlocks, reservedFirstBlocks, targetDisk);
            return;
        }
        validateExistingFileAllocation(file, claimedBlocks, targetDisk);
    }

    private void hydrateFileAllocation(
            FileNode file,
            boolean[] claimedBlocks,
            boolean[] reservedFirstBlocks,
            SimulatedDisk targetDisk) {
        int[] chain = new int[file.getSizeInBlocks()];
        chain[0] = file.getFirstBlockIndex();
        markClaimed(chain[0], file, claimedBlocks);

        int searchStart = (chain[0] + 1) % targetDisk.getTotalBlocks();
        for (int i = 1; i < chain.length; i++) {
            int blockIndex = findNextFreeUnclaimedBlock(searchStart, claimedBlocks, reservedFirstBlocks, targetDisk);
            if (blockIndex == SimulatedDisk.NO_FREE_BLOCK) {
                throw new IllegalArgumentException("unable to hydrate disk allocation for file: " + file.getId());
            }
            chain[i] = blockIndex;
            markClaimed(blockIndex, file, claimedBlocks);
            searchStart = (blockIndex + 1) % targetDisk.getTotalBlocks();
        }

        for (int i = 0; i < chain.length; i++) {
            int nextBlockIndex = (i == chain.length - 1) ? DiskBlock.NO_NEXT_BLOCK : chain[i + 1];
            targetDisk.allocateBlock(chain[i], file.getId(), nextBlockIndex, file.isSystemFile());
        }
    }

    private void validateExistingFileAllocation(
            FileNode file,
            boolean[] claimedBlocks,
            SimulatedDisk targetDisk) {
        int currentBlockIndex = file.getFirstBlockIndex();
        for (int i = 0; i < file.getSizeInBlocks(); i++) {
            if (!targetDisk.isValidIndex(currentBlockIndex)) {
                throw new IllegalArgumentException("disk chain points outside disk for file: " + file.getId());
            }

            DiskBlock block = targetDisk.getBlock(currentBlockIndex);
            if (block.isFree()) {
                throw new IllegalArgumentException("disk chain is incomplete for file: " + file.getId());
            }
            if (!file.getId().equals(block.getOwnerFileId())) {
                throw new IllegalArgumentException("disk block owner mismatch for file: " + file.getId());
            }
            if (block.isSystemReserved() != file.isSystemFile()) {
                throw new IllegalArgumentException("disk block system flag mismatch for file: " + file.getId());
            }

            markClaimed(currentBlockIndex, file, claimedBlocks);

            if (i == file.getSizeInBlocks() - 1) {
                if (block.getNextBlockIndex() != DiskBlock.NO_NEXT_BLOCK) {
                    throw new IllegalArgumentException("disk chain length mismatch for file: " + file.getId());
                }
                return;
            }

            currentBlockIndex = block.getNextBlockIndex();
        }
    }

    private int findNextFreeUnclaimedBlock(
            int startIndex,
            boolean[] claimedBlocks,
            boolean[] reservedFirstBlocks,
            SimulatedDisk targetDisk) {
        for (int offset = 0; offset < targetDisk.getTotalBlocks(); offset++) {
            int candidate = (startIndex + offset) % targetDisk.getTotalBlocks();
            if (!claimedBlocks[candidate]
                    && !reservedFirstBlocks[candidate]
                    && targetDisk.getBlock(candidate).isFree()) {
                return candidate;
            }
        }
        return SimulatedDisk.NO_FREE_BLOCK;
    }

    private void markClaimed(int blockIndex, FileNode file, boolean[] claimedBlocks) {
        if (claimedBlocks[blockIndex]) {
            throw new IllegalArgumentException("duplicate block claim detected for file: " + file.getId());
        }
        claimedBlocks[blockIndex] = true;
    }

    private void ensureNoOrphanedOccupiedBlocks(boolean[] claimedBlocks, SimulatedDisk targetDisk) {
        for (int blockIndex = 0; blockIndex < targetDisk.getTotalBlocks(); blockIndex++) {
            if (!claimedBlocks[blockIndex] && !targetDisk.getBlock(blockIndex).isFree()) {
                throw new IllegalArgumentException(
                        "disk contains occupied block without filesystem owner: " + blockIndex);
            }
        }
    }

    private void requireApplicationState(SimulationApplicationState applicationState) {
        if (applicationState == null) {
            throw new IllegalArgumentException("applicationState cannot be null");
        }
    }

    private void requireDisk(SimulatedDisk disk) {
        if (disk == null) {
            throw new IllegalArgumentException("disk cannot be null");
        }
    }
}

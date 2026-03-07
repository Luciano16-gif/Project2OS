package ve.edu.unimet.so.project2.datastructures;

public final class Compare {

    private Compare() {
    }

    /**
     * Minimal comparator
     * Returns negative if a < b, zero if equal, positive if a > b.
     */
    public interface Comparator<T> {
        int compare(T a, T b);
    }

}

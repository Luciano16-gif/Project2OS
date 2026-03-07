package ve.edu.unimet.so.project2.datastructures;

public class OrderedList<T> {

    public interface Visitor<T> {
        void visit(T item);
    }

    private final Compare.Comparator<T> comparator;
    private Object[] data;
    private int size;

    public OrderedList(Compare.Comparator<T> comparator) {
        this(comparator, 10);
    }

    public OrderedList(Compare.Comparator<T> comparator, int initialCapacity) {
        if (comparator == null) {
            throw new IllegalArgumentException("comparator cannot be null");
        }
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be > 0");
        }
        this.comparator = comparator;
        this.data = new Object[initialCapacity];
        this.size = 0;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int capacity() {
        return data.length;
    }

    public void add(T element) {
        if (element == null) {
            throw new IllegalArgumentException("element cannot be null");
        }
        ensureCapacity(size + 1);
        int insertIndex = findInsertIndex(element);
        if (insertIndex < size) {
            System.arraycopy(data, insertIndex, data, insertIndex + 1, size - insertIndex);
        }
        data[insertIndex] = element;
        size++;
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        checkIndex(index);
        return (T) data[index];
    }

    @SuppressWarnings("unchecked")
    public T peekFirst() {
        if (size == 0) return null;
        return (T) data[0];
    }

    @SuppressWarnings("unchecked")
    public T pollFirst() {
        if (size == 0) return null;
        T removed = (T) data[0];
        removeAt(0);
        return removed;
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        checkIndex(index);
        T removed = (T) data[index];
        int numMoved = size - index - 1;
        if (numMoved > 0) {
            System.arraycopy(data, index + 1, data, index, numMoved);
        }
        data[--size] = null;
        return removed;
    }

    public boolean removeFirst(T target) {
        if (target == null) return false;
        for (int i = 0; i < size; i++) {
            if (data[i] == target) {
                removeAt(i);
                return true;
            }
        }
        return false;
    }

    public void clear() {
        for (int i = 0; i < size; i++) {
            data[i] = null;
        }
        size = 0;
    }

    /** Returns a compact copy of the elements (length == size). */
    public Object[] toArray() {
        Object[] out = new Object[size];
        System.arraycopy(data, 0, out, 0, size);
        return out;
    }

    /** Useful for GUI refresh/debug without exposing internal arrays. */
    public void forEach(Visitor<T> visitor) {
        if (visitor == null) {
            throw new IllegalArgumentException("visitor cannot be null");
        }
        for (int i = 0; i < size; i++) {
            @SuppressWarnings("unchecked")
            T item = (T) data[i];
            visitor.visit(item);
        }
    }

    private int findInsertIndex(T element) {
        int i = 0;
        while (i < size) {
            @SuppressWarnings("unchecked")
            T current = (T) data[i];
            if (comparator.compare(current, element) > 0) {
                break;
            }
            i++;
        }
        return i;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity <= data.length) return;
        int newCapacity = data.length * 2;
        if (newCapacity < minCapacity) newCapacity = minCapacity;
        Object[] newData = new Object[newCapacity];
        System.arraycopy(data, 0, newData, 0, size);
        data = newData;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
        }
    }
}

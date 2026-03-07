package ve.edu.unimet.so.project2.datastructures;

public class SimpleList<T> {
    
    public interface Visitor<T> {
        void visit(T item);
    }

    private Object[] data;
    private int size;

    public SimpleList() {
        this(10);
    }

    public SimpleList(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be > 0");
        }
        this.data = new Object[initialCapacity];
        this.size = 0;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Adding this for debugging after what happened in data structures. */
    public int capacity() {
        return data.length;
    }

    public void add(T element) {
        if (element == null) {
            throw new IllegalArgumentException("element cannot be null");
        }
        ensureCapacity(size + 1);
        data[size++] = element;
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        checkIndex(index);
        return (T) data[index];
    }

    public void set(int index, T element) {
        checkIndex(index);
        if (element == null) {
            throw new IllegalArgumentException("element cannot be null");
        }
        data[index] = element;
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        checkIndex(index);

        T removed = (T) data[index];

        int numMoved = size - index - 1;
        if (numMoved > 0) {
            System.arraycopy(data, index + 1, data, index, numMoved);
        }

        data[--size] = null; // help GC
        return removed;
    }


    public boolean removeFirst(T target) {
        if (target == null) return false;

        for (int i = 0; i < size; i++) {
            Object cur = data[i];

            if (cur == target) {
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

    /** Useful for GUI refresh/debug without exposing internal nodes/arrays. */
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

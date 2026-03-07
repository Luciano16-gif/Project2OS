package ve.edu.unimet.so.project2.datastructures;

public class LinkedQueue<T> {

    public interface Visitor<T> {
        void visit(T item);
    }

    private static final class Node<T> {
        final T value;
        private Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private Node<T> head;
    private Node<T> tail;
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void enqueue(T item) {
        if (item == null) {
            throw new IllegalArgumentException("Item cannot be null");
        }
        Node<T> newNode = new Node<>(item);
        if (tail == null) {
            head = tail = newNode;
        } else {
            tail.next = newNode;
            tail = newNode;
        }
        size++;
    }

    public T dequeue() {
        if (head == null) {
            return null;
        }

        T value = head.value;
        head = head.next;
        if (head == null) {
            tail = null;
        }
        size--;
        return value;
    }

    public T peek() {
        if (head == null) {
            return null;
        }
        return head.value;
    }

    public T peekLast() {
        if (tail == null) {
            return null;
        }
        return tail.value;
    }

    public boolean removeFirst(T target) {
        if (target == null || head == null) {
            return false;
        }

        if (head.value == target) {
            dequeue();
            return true;
        }

        Node<T> previous = head;
        Node<T> current = head.next;
        while (current != null) {
            if (current.value == target) {
                previous.next = current.next;
                if (current == tail) {
                    tail = previous;
                }
                size--;
                return true;
            }
            previous = current;
            current = current.next;
        }

        return false;
    }

    public Object[] toArray() {
        Object[] out = new Object[size];
        Node<T> current = head;
        int index = 0;
        while (current != null) {
            out[index++] = current.value;
            current = current.next;
        }
        return out;
    }

    public void forEach(Visitor<T> visitor) {
        if (visitor == null) {
            throw new IllegalArgumentException("visitor cannot be null");
        }

        Node<T> current = head;
        while (current != null) {
            visitor.visit(current.value);
            current = current.next;
        }
    }

    public void clear() {
        head = null;
        tail = null;
        size = 0;
    }
}

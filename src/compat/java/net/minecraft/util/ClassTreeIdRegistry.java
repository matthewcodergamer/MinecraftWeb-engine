package net.minecraft.util;

/**
 * Browser-safe replacement for Minecraft's {@code ClassTreeIdRegistry}.
 *
 * <p>The vanilla implementation keys a fastutil {@code Object2IntOpenHashMap}
 * by {@link Class}. TeaVM's Wasm GC runtime stores an object's identity hash in
 * the same slot it later uses for the object's monitor. Synchronizing on a
 * class object (which the client does during class initialization) changes its
 * {@code hashCode()}, so fastutil can no longer find keys it inserted a moment
 * earlier. Every accessor then receives id 0 and mob creation fails with
 * {@code Duplicate id value for 0!}.</p>
 *
 * <p>The registry only ever holds a few hundred classes, so a linear identity
 * scan is both faster than hashing here and immune to that runtime detail. The
 * browser client runs Java on one JavaScript thread and these methods never
 * suspend, so no locking is required.</p>
 */
public class ClassTreeIdRegistry {
    public static final int NO_ID_VALUE = -1;

    private Class<?>[] classes = new Class<?>[16];
    private int[] ids = new int[16];
    private int size;

    public int getLastIdFor(Class<?> clazz) {
        for (Class<?> current = clazz; current != null && current != Object.class;
                current = current.getSuperclass()) {
            int index = indexOf(current);
            if (index >= 0) {
                return ids[index];
            }
        }
        return NO_ID_VALUE;
    }

    public int getCount(Class<?> clazz) {
        return getLastIdFor(clazz) + 1;
    }

    public int define(Class<?> clazz) {
        int lastId = getLastIdFor(clazz);
        int id = lastId == NO_ID_VALUE ? 0 : lastId + 1;
        int index = indexOf(clazz);
        if (index >= 0) {
            ids[index] = id;
        } else {
            if (size == classes.length) {
                grow();
            }
            classes[size] = clazz;
            ids[size] = id;
            size++;
        }
        return id;
    }

    private int indexOf(Class<?> clazz) {
        for (int i = 0; i < size; i++) {
            if (classes[i] == clazz) {
                return i;
            }
        }
        return -1;
    }

    private void grow() {
        int capacity = classes.length * 2;
        Class<?>[] newClasses = new Class<?>[capacity];
        int[] newIds = new int[capacity];
        System.arraycopy(classes, 0, newClasses, 0, size);
        System.arraycopy(ids, 0, newIds, 0, size);
        classes = newClasses;
        ids = newIds;
    }
}

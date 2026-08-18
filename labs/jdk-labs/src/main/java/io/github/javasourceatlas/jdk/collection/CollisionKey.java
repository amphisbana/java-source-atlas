package io.github.javasourceatlas.jdk.collection;

/**
 * 用于稳定制造哈希碰撞的不可变键。
 */
public final class CollisionKey {

    private final int id;
    private final int fixedHash;

    /**
     * 创建一个返回固定哈希值的实验键。
     *
     * @param id        用于区分键相等性的编号
     * @param fixedHash 固定返回的哈希值
     */
    public CollisionKey(int id, int fixedHash) {
        this.id = id;
        this.fixedHash = fixedHash;
    }

    /**
     * 返回实验键的业务编号。
     *
     * @return 键编号
     */
    public int getId() {
        return id;
    }

    /**
     * 返回构造时指定的哈希值，以便多个不同键进入同一个桶。
     *
     * @return 固定哈希值
     */
    @Override
    public int hashCode() {
        return fixedHash;
    }

    /**
     * 只按编号判断键是否相等，确保碰撞键仍然是不同映射。
     *
     * @param other 待比较对象
     * @return 编号相同时返回 true
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CollisionKey)) {
            return false;
        }
        CollisionKey that = (CollisionKey) other;
        return id == that.id;
    }

    /**
     * 输出便于调试器和控制台识别的键文本。
     *
     * @return 键描述
     */
    @Override
    public String toString() {
        return "CollisionKey{" +
                "id=" + id +
                ", fixedHash=" + fixedHash +
                '}';
    }
}

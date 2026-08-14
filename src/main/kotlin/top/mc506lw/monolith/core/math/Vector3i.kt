package top.mc506lw.monolith.core.math

data class Vector3i(val x: Int, val y: Int, val z: Int) {
    operator fun plus(other: Vector3i) = Vector3i(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3i) = Vector3i(x - other.x, y - other.y, z - other.z)
    operator fun unaryMinus() = Vector3i(-x, -y, -z)
    
    /**
     * 无损打包为 Long：x/y/z 各占 21 位（位 42-62 / 21-41 / 0-20）。
     * 旧实现把 z 左移 62 位，只保留 z 的最低 2 位 —— 结构在 Z 方向超过 4 格时
     * (0,0,0)/(0,0,4)/(0,0,8)… 全部映射为同一个 Long，导致 HashSet/HashMap 大量
     * 碰撞退化成红黑树（putTreeVal 数秒）。21 位足够覆盖 ±1048575 的坐标范围。
     */
    fun toLong(): Long {
        return ((x.toLong() and 0x1FFFFF) shl 42) or ((y.toLong() and 0x1FFFFF) shl 21) or (z.toLong() and 0x1FFFFF)
    }

    fun fromLong(value: Long): Vector3i {
        val x = ((value shr 42).toInt() shl 11) shr 11
        val y = ((value shr 21).toInt() shl 11) shr 11
        val z = (value.toInt() shl 11) shr 11
        return Vector3i(x, y, z)
    }

    companion object {
        val ZERO = Vector3i(0, 0, 0)
        val UP = Vector3i(0, 1, 0)
        val DOWN = Vector3i(0, -1, 0)
        val NORTH = Vector3i(0, 0, -1)
        val SOUTH = Vector3i(0, 0, 1)
        val WEST = Vector3i(-1, 0, 0)
        val EAST = Vector3i(1, 0, 0)
    }
}

package top.mc506lw.monolith.core.transform

import top.mc506lw.monolith.core.math.Matrix3x3
import top.mc506lw.monolith.core.math.Vector3i
import org.joml.Vector3f

class CoordinateTransform(
    val facing: Facing = Facing.NORTH,
    val isFlipped: Boolean = false
) {
    private val rotationMatrix: Matrix3x3 = when (facing.rotationSteps) {
        0 -> Matrix3x3.IDENTITY
        1 -> Matrix3x3.rotationY90()
        2 -> Matrix3x3.rotationY180()
        3 -> Matrix3x3.rotationY270()
        else -> Matrix3x3.IDENTITY
    }
    
    private val mirrorMatrix: Matrix3x3 = if (isFlipped) {
        when (facing) {
            Facing.NORTH, Facing.SOUTH -> Matrix3x3.mirrorX()
            Facing.EAST, Facing.WEST -> Matrix3x3.mirrorZ()
        }
    } else {
        Matrix3x3.IDENTITY
    }
    
    private val combinedMatrix: Matrix3x3 = mirrorMatrix * rotationMatrix

    private val inverseRotationMatrix: Matrix3x3 = when (facing.rotationSteps) {
        1 -> Matrix3x3.rotationY270()
        2 -> Matrix3x3.rotationY180()
        3 -> Matrix3x3.rotationY90()
        else -> Matrix3x3.IDENTITY
    }

    /** inverse(mirror * rotation) = inverse(rotation) * inverse(mirror) = inverseRotation * mirror */
    private val inverseMatrix: Matrix3x3 = inverseRotationMatrix * mirrorMatrix
    
    fun transform(relativePos: Vector3i): Vector3i {
        return combinedMatrix.transform(relativePos)
    }

    fun transform(relativePos: Vector3f): Vector3f {
        val rotated = when (facing.rotationSteps) {
            1 -> Vector3f(relativePos.z, relativePos.y, -relativePos.x)
            2 -> Vector3f(-relativePos.x, relativePos.y, -relativePos.z)
            3 -> Vector3f(-relativePos.z, relativePos.y, relativePos.x)
            else -> Vector3f(relativePos)
        }
        if (isFlipped) {
            if (facing == Facing.NORTH || facing == Facing.SOUTH) rotated.x = -rotated.x else rotated.z = -rotated.z
        }
        return rotated
    }
    
    fun inverseTransform(worldPos: Vector3i): Vector3i {
        return inverseMatrix.transform(worldPos)
    }
    
    fun toWorldPosition(controllerPos: Vector3i, relativePos: Vector3i, centerOffset: Vector3i): Vector3i {
        val transformed = transform(relativePos - centerOffset)
        return controllerPos + transformed
    }
    
    fun toRelativePosition(worldPos: Vector3i, controllerPos: Vector3i, centerOffset: Vector3i): Vector3i {
        val diff = worldPos - controllerPos
        return inverseTransform(diff) + centerOffset
    }
    
    companion object {
        val DEFAULT = CoordinateTransform(Facing.NORTH, false)
    }
}

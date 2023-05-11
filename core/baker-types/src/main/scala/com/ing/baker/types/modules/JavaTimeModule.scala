package com.ing.baker.types.modules

import com.ing.baker.types._

import java.time._

/**
 * Add support for Java time objects
 **/
class JavaTimeModule extends TypeModule {

  override def isApplicable(javaType: java.lang.reflect.Type): Boolean =
      isAssignableToBaseClass(javaType, classOf[LocalDateTime]) ||
      isAssignableToBaseClass(javaType, classOf[LocalDate]) ||
      isAssignableToBaseClass(javaType, classOf[Instant])

  override def readType(context: TypeAdapter, javaType: java.lang.reflect.Type): Type = Date

  override def toJava(context: TypeAdapter, value: Value, javaType: java.lang.reflect.Type): Any =
    (value, javaType) match {
      case (NullValue, _) => null
      case (PrimitiveValue(millis: Long), clazz: Class[_]) if classOf[LocalDateTime].isAssignableFrom(clazz) =>
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
      case (PrimitiveValue(millis: Long), clazz: Class[_]) if classOf[LocalDate].isAssignableFrom(clazz) =>
        LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
      case (PrimitiveValue(millis: Long), clazz: Class[_]) if classOf[Instant].isAssignableFrom(clazz) =>
        Instant.ofEpochMilli(millis)
      case unsupportedType =>
        throw new IllegalArgumentException(s"UnsupportedType: $unsupportedType")
    }

  override def fromJava(context: TypeAdapter, obj: Any): Value =
    obj match {
      case localDate: LocalDate => PrimitiveValue(localDate.atStartOfDay.atZone(ZoneId.systemDefault()).toInstant
        .toEpochMilli)
      case localDateTime: LocalDateTime => PrimitiveValue(localDateTime.atZone(ZoneId.systemDefault()).toInstant.toEpochMilli)
      case instant: Instant => PrimitiveValue(instant.toEpochMilli)
    }
}
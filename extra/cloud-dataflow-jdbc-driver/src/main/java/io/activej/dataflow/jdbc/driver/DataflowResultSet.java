package io.activej.dataflow.jdbc.driver;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.activej.dataflow.jdbc.driver.utils.InstantHolder;
import org.apache.calcite.avatica.AvaticaResultSet;
import org.apache.calcite.avatica.AvaticaStatement;
import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.avatica.Meta;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.calcite.avatica.remote.JsonService.MAPPER;

public class DataflowResultSet extends AvaticaResultSet {

	private static final TypeFactory TYPE_FACTORY = TypeFactory.defaultInstance();
	private static final Map<String, JavaType> JAVA_TYPE_CACHE = new ConcurrentHashMap<>();

	DataflowResultSet(AvaticaStatement statement,
		Meta.Signature signature,
		ResultSetMetaData resultSetMetaData, TimeZone timeZone,
		Meta.Frame firstFrame) throws SQLException {

		super(statement, null, signature, resultSetMetaData, timeZone, firstFrame);
	}

	@Override
	public Object getObject(String columnLabel) throws SQLException {
		int column = findColumn(columnLabel);
		return getObject(column);
	}

	@Override
	public Object getObject(int columnIndex) throws SQLException {
		Object result = doGetObject(columnIndex, null);

		if (result instanceof Instant instant) {
			return new InstantHolder(instant);
		}

		return result;
	}

	@Override
	public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
		Object object = doGetObject(columnIndex, type);

		if (type == InstantHolder.class && object instanceof Instant instant) {
			//noinspection unchecked
			return (T) new InstantHolder(instant);
		}

		return MAPPER.convertValue(object, type);
	}

	private Object doGetObject(int columnIndex, @Nullable Class<?> expectedClass) throws SQLException {
		Object result = super.getObject(columnIndex);

		ColumnMetaData columnMetaData = signature.columns.get(columnIndex - 1);
		ColumnMetaData.AvaticaType type = columnMetaData.type;

		JavaType javaType = expectedClass == null ?
			getJavaType(type) :
			TYPE_FACTORY.constructSimpleType(expectedClass, new JavaType[0]);

		if (result == null ||
			javaType == TypeFactory.unknownType() ||
			result instanceof Array ||
			javaType.getRawClass().isAssignableFrom(result.getClass())
		) {
			return result;
		}

		return MAPPER.convertValue(result, javaType);
	}

	private static JavaType getJavaType(ColumnMetaData.AvaticaType avaticaType) {
		String name = avaticaType.getName();
		JavaType javaType = JAVA_TYPE_CACHE.get(name);
		if (javaType != null) {
			return javaType;
		}
		if (avaticaType instanceof ColumnMetaData.ArrayType arrayType) {
			JavaType componentType = getJavaType(arrayType.getComponent());
			CollectionType listType = TYPE_FACTORY.constructCollectionType(List.class, componentType);
			JAVA_TYPE_CACHE.put(name, listType);
			return listType;
		}

		MapTypes mapTypes = extractMapTypes(avaticaType);
		if (mapTypes != null) {
			MapType mapType = TYPE_FACTORY.constructMapType(Map.class, mapTypes.keyType, mapTypes.valueType);
			JAVA_TYPE_CACHE.put(name, mapType);
			return mapType;
		}

		return resolveSimple(name);
	}

	private static JavaType resolveSimple(String name) {
		return JAVA_TYPE_CACHE.computeIfAbsent(name, $ -> {
			try {
				Class<?> aClass = Class.forName(name);
				return TYPE_FACTORY.constructSimpleType(aClass, new JavaType[0]);
			} catch (ClassNotFoundException e) {
				return TypeFactory.unknownType();
			}
		});
	}

	private static @Nullable MapTypes extractMapTypes(ColumnMetaData.AvaticaType avaticaType) {
		String name = avaticaType.getName();
		if (!name.startsWith("MAP(") || !name.endsWith(")")) return null;

		String componentPart = name.substring(4, name.length() - 1);
		String[] components = componentPart.split(",");
		if (components.length != 2) return null;

		return new MapTypes(
			resolveSimple(components[0]),
			resolveSimple(components[1])
		);
	}

	@Override
	public Date getDate(int columnIndex) {
		throw new UnsupportedOperationException("java.sql.Date is not supported");
	}

	@Override
	public Timestamp getTimestamp(int columnIndex) throws SQLException {
		throw new UnsupportedOperationException("java.sql.Timestamp is not supported");
	}

	@Override
	public Time getTime(int columnIndex) {
		throw new UnsupportedOperationException("java.sql.Time is not supported");
	}

	public record MapTypes(JavaType keyType, JavaType valueType) {
	}
}

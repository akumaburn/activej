package io.activej.dataflow.calcite.operand.impl;

import io.activej.common.annotation.ExposedInternals;
import io.activej.dataflow.calcite.Value;
import io.activej.dataflow.calcite.operand.Operand;
import io.activej.record.Record;
import io.activej.record.RecordScheme;
import org.apache.calcite.rex.RexDynamicParam;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@ExposedInternals
public final class Scalar implements Operand<Scalar> {
	public final Value value;

	public Scalar(Value value) {
		this.value = value;
	}

	@Override
	public <T> T getValue(Record record) {
		//noinspection unchecked
		return (T) value.getValue();
	}

	@Override
	public Type getFieldType(RecordScheme original) {
		return value.getType();
	}

	@Override
	public String getFieldName(RecordScheme original) {
		if (!value.isMaterialized()) {
			return "?";
		}
		Object actualValue = value.getValue();
		if (value.getType() == String.class && actualValue != null) {
			return "'" + actualValue + "'";
		}
		return Objects.toString(actualValue);
	}

	@Override
	public Scalar materialize(List<Object> params) {
		return new Scalar(value.materialize(params));
	}

	@Override
	public List<RexDynamicParam> getParams() {
		return value.isMaterialized() ?
			Collections.emptyList() :
			Collections.singletonList(value.getDynamicParam());
	}

	@Override
	public String toString() {
		return "Scalar[value=" + value + ']';
	}

}

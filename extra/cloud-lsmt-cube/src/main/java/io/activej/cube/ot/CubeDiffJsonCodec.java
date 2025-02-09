/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.cube.ot;

import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import com.dslplatform.json.ParsingException;
import io.activej.aggregation.Aggregation;
import io.activej.aggregation.ot.AggregationDiff;
import io.activej.aggregation.ot.AggregationDiffJsonCodec;
import io.activej.aggregation.util.JsonCodec;
import io.activej.cube.Cube;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.dslplatform.json.JsonWriter.*;

public class CubeDiffJsonCodec implements JsonCodec<CubeDiff> {
	private final Map<String, AggregationDiffJsonCodec> aggregationDiffCodecs;

	private CubeDiffJsonCodec(Map<String, AggregationDiffJsonCodec> aggregationDiffCodecs) {
		this.aggregationDiffCodecs = aggregationDiffCodecs;
	}

	public static CubeDiffJsonCodec create(Cube cube) {
		Map<String, AggregationDiffJsonCodec> aggregationDiffCodecs = new LinkedHashMap<>();

		for (String aggregationId : cube.getAggregationIds()) {
			Aggregation aggregation = cube.getAggregation(aggregationId);
			AggregationDiffJsonCodec aggregationDiffCodec = AggregationDiffJsonCodec.create(aggregation.getStructure());
			aggregationDiffCodecs.put(aggregationId, aggregationDiffCodec);
		}
		return new CubeDiffJsonCodec(aggregationDiffCodecs);
	}

	@Override
	public void write(JsonWriter writer, CubeDiff cubeDiff) {
		assert cubeDiff != null;
		writer.writeByte(OBJECT_START);
		if (cubeDiff.isEmpty() || aggregationDiffCodecs.isEmpty()) {
			writer.writeByte(OBJECT_END);
			return;
		}

		Iterator<Map.Entry<String, AggregationDiffJsonCodec>> iterator = aggregationDiffCodecs.entrySet().iterator();
		boolean first = true;
		while (true) {
			Map.Entry<String, AggregationDiffJsonCodec> entry = iterator.next();
			AggregationDiff aggregationDiff = cubeDiff.get(entry.getKey());
			if (aggregationDiff != null) {
				if (!first) {
					writer.writeByte(COMMA);
				}
				first = false;
				writer.writeString(entry.getKey());
				writer.writeByte(SEMI);
				entry.getValue().write(writer, aggregationDiff);
			}
			if (!iterator.hasNext()) {
				writer.writeByte(OBJECT_END);
				return;
			}
		}
	}

	@Override
	public CubeDiff read(JsonReader reader) throws IOException {
		if (reader.last() != OBJECT_START) throw reader.newParseError("Expected '{'");

		if (reader.getNextToken() == OBJECT_END) {
			return CubeDiff.empty();
		}

		Map<String, AggregationDiff> map = new LinkedHashMap<>();
		String aggregation = reader.readKey();
		AggregationDiffJsonCodec aggregationDiffCodec = aggregationDiffCodecs.get(aggregation);
		if (aggregationDiffCodec == null) {
			throw ParsingException.create("Unknown aggregation: " + aggregation, true);
		}
		map.put(aggregation, aggregationDiffCodec.read(reader));

		while (reader.getNextToken() == ',') {
			reader.getNextToken();
			aggregation = reader.readKey();
			aggregationDiffCodec = aggregationDiffCodecs.get(aggregation);
			if (aggregationDiffCodec == null) {
				throw ParsingException.create("Unknown aggregation: " + aggregation, true);
			}
			AggregationDiff aggregationDiff = aggregationDiffCodec.read(reader);
			map.put(aggregation, aggregationDiff);
		}
		reader.checkObjectEnd();
		return CubeDiff.of(map);
	}
}

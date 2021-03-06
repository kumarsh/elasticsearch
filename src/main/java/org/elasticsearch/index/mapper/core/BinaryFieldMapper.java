/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper.core;

import com.carrotsearch.hppc.ObjectArrayList;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.store.ByteArrayDataOutput;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.Version;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressorFactory;
import org.elasticsearch.common.compress.NotXContentException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.fielddata.FieldDataType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.ParseContext;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.index.mapper.MapperBuilders.binaryField;
import static org.elasticsearch.index.mapper.core.TypeParsers.parseField;

/**
 *
 */
public class BinaryFieldMapper extends AbstractFieldMapper {

    public static final String CONTENT_TYPE = "binary";
    private static final ParseField COMPRESS = new ParseField("compress").withAllDeprecated("no replacement, implemented at the codec level");
    private static final ParseField COMPRESS_THRESHOLD = new ParseField("compress_threshold").withAllDeprecated("no replacement");


    public static class Defaults extends AbstractFieldMapper.Defaults {
        public static final FieldType FIELD_TYPE = new FieldType(AbstractFieldMapper.Defaults.FIELD_TYPE);

        static {
            FIELD_TYPE.setIndexOptions(IndexOptions.NONE);
            FIELD_TYPE.freeze();
        }
    }

    public static class Builder extends AbstractFieldMapper.Builder<Builder, BinaryFieldMapper> {

        public Builder(String name) {
            super(name, new FieldType(Defaults.FIELD_TYPE));
            builder = this;
        }

        @Override
        public BinaryFieldMapper build(BuilderContext context) {
            return new BinaryFieldMapper(buildNames(context), fieldType, docValues,
                    fieldDataSettings, context.indexSettings(), multiFieldsBuilder.build(this, context), copyTo);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override
        public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            BinaryFieldMapper.Builder builder = binaryField(name);
            parseField(builder, name, node, parserContext);
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String fieldName = entry.getKey();
                if (parserContext.indexVersionCreated().before(Version.V_2_0_0) &&
                        (COMPRESS.match(fieldName) || COMPRESS_THRESHOLD.match(fieldName))) {
                    iterator.remove();
                }
            }
            return builder;
        }
    }

    protected BinaryFieldMapper(Names names, FieldType fieldType, Boolean docValues,
                                @Nullable Settings fieldDataSettings, Settings indexSettings, MultiFields multiFields, CopyTo copyTo) {
        super(names, 1.0f, fieldType, docValues, null, null, null, null, fieldDataSettings, indexSettings, multiFields, copyTo);
    }

    @Override
    public FieldType defaultFieldType() {
        return Defaults.FIELD_TYPE;
    }

    @Override
    public FieldDataType defaultFieldDataType() {
        return new FieldDataType("binary");
    }

    @Override
    public Object valueForSearch(Object value) {
        return value(value);
    }

    @Override
    public BytesReference value(Object value) {
        if (value == null) {
            return null;
        }

        BytesReference bytes;
        if (value instanceof BytesRef) {
            bytes = new BytesArray((BytesRef) value);
        } else if (value instanceof BytesReference) {
            bytes = (BytesReference) value;
        } else if (value instanceof byte[]) {
            bytes = new BytesArray((byte[]) value);
        } else {
            try {
                bytes = new BytesArray(Base64.decode(value.toString()));
            } catch (IOException e) {
                throw new ElasticsearchParseException("failed to convert bytes", e);
            }
        }
        try {
            if (indexCreatedBefore2x) {
                try {
                    return CompressorFactory.uncompressIfNeeded(bytes);
                } catch (NotXContentException e) {
                    // NOTE: previous versions of Elasticsearch used to try to detect if
                    // data was compressed. However this could cause decompression failures
                    // as a user may have submitted arbitrary data which looks like it is
                    // compressed to elasticsearch but is not. So we removed the ability to
                    // compress binary fields and keep this empty catch block for backward
                    // compatibility with 1.x
                }
            }
            return bytes;
        } catch (IOException e) {
            throw new ElasticsearchParseException("failed to decompress source", e);
        }
    }

    @Override
    protected void parseCreateField(ParseContext context, List<Field> fields) throws IOException {
        if (!fieldType().stored() && !hasDocValues()) {
            return;
        }
        byte[] value = context.parseExternalValue(byte[].class);
        if (value == null) {
            if (context.parser().currentToken() == XContentParser.Token.VALUE_NULL) {
                return;
            } else {
                value = context.parser().binaryValue();
            }
        }
        if (value == null) {
            return;
        }
        if (fieldType().stored()) {
            fields.add(new Field(names.indexName(), value, fieldType));
        }

        if (hasDocValues()) {
            CustomBinaryDocValuesField field = (CustomBinaryDocValuesField) context.doc().getByKey(names().indexName());
            if (field == null) {
                field = new CustomBinaryDocValuesField(names().indexName(), value);
                context.doc().addWithKey(names().indexName(), field);
            } else {
                field.add(value);
            }
        }

    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    public static class CustomBinaryDocValuesField extends NumberFieldMapper.CustomNumericDocValuesField {

        public static final FieldType TYPE = new FieldType();
        static {
            TYPE.setDocValuesType(DocValuesType.BINARY);
            TYPE.freeze();
        }

        private final ObjectArrayList<byte[]> bytesList;

        private int totalSize = 0;

        public CustomBinaryDocValuesField(String  name, byte[] bytes) {
            super(name);
            bytesList = new ObjectArrayList<>();
            add(bytes);
        }

        public void add(byte[] bytes) {
            bytesList.add(bytes);
            totalSize += bytes.length;
        }

        @Override
        public BytesRef binaryValue() {
            try {
                CollectionUtils.sortAndDedup(bytesList);
                int size = bytesList.size();
                final byte[] bytes = new byte[totalSize + (size + 1) * 5];
                ByteArrayDataOutput out = new ByteArrayDataOutput(bytes);
                out.writeVInt(size);  // write total number of values
                for (int i = 0; i < size; i ++) {
                    final byte[] value = bytesList.get(i);
                    int valueLength = value.length;
                    out.writeVInt(valueLength);
                    out.writeBytes(value, 0, valueLength);
                }
                return new BytesRef(bytes, 0, out.getPosition());
            } catch (IOException e) {
                throw new ElasticsearchException("Failed to get binary value", e);
            }

        }
    }
}

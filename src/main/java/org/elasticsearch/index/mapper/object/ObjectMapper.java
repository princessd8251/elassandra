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

package org.elasticsearch.index.mapper.object;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeBooleanValue;
import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeIntegerValue;
import static org.elasticsearch.index.mapper.MapperBuilders.object;
import static org.elasticsearch.index.mapper.core.TypeParsers.parsePathType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.QueryWrapperFilter;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.Version;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.CopyOnWriteHashMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.ContentPath;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.DocumentMapperParser;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MapperUtils;
import org.elasticsearch.index.mapper.MergeMappingException;
import org.elasticsearch.index.mapper.MergeResult;
import org.elasticsearch.index.mapper.MetadataFieldMapper;
import org.elasticsearch.index.mapper.core.TypeParsers;
import org.elasticsearch.index.mapper.internal.AllFieldMapper;
import org.elasticsearch.index.mapper.internal.TypeFieldMapper;

import com.google.common.collect.Iterables;

public class ObjectMapper extends Mapper implements AllFieldMapper.IncludeInAll, Cloneable {

    public static final String CONTENT_TYPE = "object";
    public static final String NESTED_CONTENT_TYPE = "nested";

    public static class Defaults {
        public static final boolean ENABLED = true;
        public static final Nested NESTED = Nested.NO;
        public static final Dynamic DYNAMIC = null; // not set, inherited from root
        public static final ContentPath.Type PATH_TYPE = ContentPath.Type.FULL;
        
        public static final CqlCollection CQL_COLLECTION = CqlCollection.LIST;
        public static final CqlStruct CQL_STRUCT = CqlStruct.UDT;
        public static final boolean  CQL_MANDATORY = true; // if true, force a read if field is missing when indexing.
        public static final boolean  CQL_PARTITION_KEY = false;
        public static final boolean  CQL_STATIC_COLUMN = false;
        public static final int  CQL_PRIMARY_KEY_ORDER = -1;
    }

    public static enum Dynamic {
        TRUE,
        FALSE,
        STRICT
    }

    
    public static class Nested {

        public static final Nested NO = new Nested(false, false, false);

        public static Nested newNested(boolean includeInParent, boolean includeInRoot) {
            return new Nested(true, includeInParent, includeInRoot);
        }

        private final boolean nested;

        private final boolean includeInParent;

        private final boolean includeInRoot;

        private Nested(boolean nested, boolean includeInParent, boolean includeInRoot) {
            this.nested = nested;
            this.includeInParent = includeInParent;
            this.includeInRoot = includeInRoot;
        }

        public boolean isNested() {
            return nested;
        }

        public boolean isIncludeInParent() {
            return includeInParent;
        }

        public boolean isIncludeInRoot() {
            return includeInRoot;
        }
    }

    public static class Builder<T extends Builder, Y extends ObjectMapper> extends Mapper.Builder<T, Y> {

        protected boolean enabled = Defaults.ENABLED;

        protected CqlCollection cqlCollection = Defaults.CQL_COLLECTION;

        protected CqlStruct cqlStruct = Defaults.CQL_STRUCT;

        protected boolean cqlMandatory = Defaults.CQL_MANDATORY;
        
        protected boolean cqlPartitionKey = Defaults.CQL_PARTITION_KEY;
        
        protected boolean cqlStaticColumn = Defaults.CQL_STATIC_COLUMN;
        
        protected int cqlPrimaryKeyOrder = Defaults.CQL_PRIMARY_KEY_ORDER;
        
        protected Nested nested = Defaults.NESTED;

        protected Dynamic dynamic = Defaults.DYNAMIC;

        protected ContentPath.Type pathType = Defaults.PATH_TYPE;

        protected Boolean includeInAll;

        protected final List<Mapper.Builder> mappersBuilders = new ArrayList<>();

        public Builder(String name) {
            super(name);
            this.builder = (T) this;
        }

        public T enabled(boolean enabled) {
            this.enabled = enabled;
            return builder;
        }
        
        public T cqlCollection(CqlCollection cqlCollection) {
            this.cqlCollection = cqlCollection;
            return builder;
        }
        
        public T cqlStruct(CqlStruct cqlStruct) {
            this.cqlStruct = cqlStruct;
            return builder;
        }
        
        public T cqlPartialUpdate(boolean cqlPartialUpdate) {
            this.cqlMandatory = cqlPartialUpdate;
            return builder;
        }
        
        public T cqlStaticColumn(boolean cqlStaticColumn) {
            this.cqlStaticColumn = cqlStaticColumn;
            return builder;
        }
        
        public T cqlPartitionKey(boolean cqlPartitionKey) {
            this.cqlPartitionKey = cqlPartitionKey;
            return builder;
        }
        
        public T cqlPrimaryKeyOrder(int cqlPrimaryKeyOrder) {
            this.cqlPrimaryKeyOrder = cqlPrimaryKeyOrder;
            return builder;
        }
        
        
        public T dynamic(Dynamic dynamic) {
            this.dynamic = dynamic;
            return builder;
        }

        public T nested(Nested nested) {
            this.nested = nested;
            return builder;
        }

        public T pathType(ContentPath.Type pathType) {
            this.pathType = pathType;
            return builder;
        }

        public T includeInAll(boolean includeInAll) {
            this.includeInAll = includeInAll;
            return builder;
        }

        public T add(Mapper.Builder builder) {
            mappersBuilders.add(builder);
            return this.builder;
        }

        @Override
        public Y build(BuilderContext context) {
            ContentPath.Type origPathType = context.path().pathType();
            context.path().pathType(pathType);
            context.path().add(name);

            Map<String, Mapper> mappers = new HashMap<>();
            for (Mapper.Builder builder : mappersBuilders) {
                Mapper mapper = builder.build(context);
                mappers.put(mapper.simpleName(), mapper);
            }
            context.path().pathType(origPathType);
            context.path().remove();

            ObjectMapper objectMapper = createMapper(name, context.path().fullPathAsText(name), cqlCollection, cqlStruct, cqlMandatory, cqlPartitionKey, cqlPrimaryKeyOrder, cqlStaticColumn, enabled, nested, dynamic, pathType, mappers, context.indexSettings());
            objectMapper.includeInAllIfNotSet(includeInAll);

            return (Y) objectMapper;
        }

        protected ObjectMapper createMapper(String name, String fullPath, CqlCollection cqlCollection, CqlStruct cqlStruct, boolean cqlPartialUpdate, boolean cqlPartitionKey, int cqlPrimaryKeyOrder, boolean cqlStaticColumn,  boolean enabled, Nested nested, Dynamic dynamic, ContentPath.Type pathType, Map<String, Mapper> mappers, @Nullable Settings settings) {
            return new ObjectMapper(name, fullPath, cqlCollection, cqlStruct, cqlPartialUpdate, cqlPartitionKey, cqlPrimaryKeyOrder, cqlStaticColumn, enabled, nested, dynamic, pathType, mappers);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override
        public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            ObjectMapper.Builder builder = createBuilder(name);
            parseNested(name, node, builder);
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String fieldName = Strings.toUnderscoreCase(entry.getKey());
                Object fieldNode = entry.getValue();
                if (parseObjectOrDocumentTypeProperties(fieldName, fieldNode, parserContext, builder) || parseObjectProperties(name, fieldName,  fieldNode, parserContext, builder)) {
                    iterator.remove();
                }
            }
            return builder;
        }

        protected static boolean parseObjectOrDocumentTypeProperties(String fieldName, Object fieldNode, ParserContext parserContext, ObjectMapper.Builder builder) {
            if (fieldName.equals("dynamic")) {
                String value = fieldNode.toString();
                if (value.equalsIgnoreCase("strict")) {
                    builder.dynamic(Dynamic.STRICT);
                } else {
                    builder.dynamic(nodeBooleanValue(fieldNode) ? Dynamic.TRUE : Dynamic.FALSE);
                }
                return true;
            } else if (fieldName.equals("enabled")) {
                builder.enabled(nodeBooleanValue(fieldNode));
                return true;
            } else if (fieldName.equals(TypeParsers.CQL_MANDATORY)) {
                builder.cqlPartialUpdate(nodeBooleanValue(fieldNode));
                return true;
            } else if (fieldName.equals(TypeParsers.CQL_PARTITION_KEY)) {
                builder.cqlPartitionKey(nodeBooleanValue(fieldNode));
                return true;
            } else if (fieldName.equals(TypeParsers.CQL_STATIC_COLUMN)) {
                builder.cqlStaticColumn(nodeBooleanValue(fieldNode));
                return true;
            } else if (fieldName.equals(TypeParsers.CQL_PRIMARY_KEY_ORDER)) {
                builder.cqlPrimaryKeyOrder(nodeIntegerValue(fieldNode));
                return true;
            } else if (fieldName.equals(TypeParsers.CQL_COLLECTION)) {
                String value = StringUtils.lowerCase(fieldNode.toString());
                switch (value) {
                case "list": builder.cqlCollection(CqlCollection.LIST); break;
                case "set": builder.cqlCollection(CqlCollection.SET); break;
                case "singleton": builder.cqlCollection(CqlCollection.SINGLETON); break;
                }
                return true;
            } else if (fieldName.equals(TypeParsers.CQL_STRUCT)) {
                String value = StringUtils.lowerCase(fieldNode.toString());
                switch (value) {
                case "tuple": builder.cqlStruct(CqlStruct.TUPLE); break;
                case "map": builder.cqlStruct(CqlStruct.MAP); break;
                case "udt": builder.cqlStruct(CqlStruct.UDT); break;
                }
                return true;
            } else if (fieldName.equals("properties")) {
                if (fieldNode instanceof Collection && ((Collection) fieldNode).isEmpty()) {
                    // nothing to do here, empty (to support "properties: []" case)
                } else if (!(fieldNode instanceof Map)) {
                    throw new ElasticsearchParseException("properties must be a map type");
                } else {
                    parseProperties(builder, (Map<String, Object>) fieldNode, parserContext);
                }
                return true;
            } else if (fieldName.equals("include_in_all")) {
                builder.includeInAll(nodeBooleanValue(fieldNode));
                return true;
            }
            return false;
        }

        protected static boolean parseObjectProperties(String name, String fieldName, Object fieldNode, ParserContext parserContext, ObjectMapper.Builder builder) {
            if (fieldName.equals("path") && parserContext.indexVersionCreated().before(Version.V_2_0_0_beta1)) {
                builder.pathType(parsePathType(name, fieldNode.toString()));
                return true;
            }
            return false;
        }

        protected static void parseNested(String name, Map<String, Object> node, ObjectMapper.Builder builder) {
            boolean nested = false;
            boolean nestedIncludeInParent = false;
            boolean nestedIncludeInRoot = false;
            Object fieldNode = node.get("type");
            if (fieldNode!=null) {
                String type = fieldNode.toString();
                if (type.equals(CONTENT_TYPE)) {
                    builder.nested = Nested.NO;
                } else if (type.equals(NESTED_CONTENT_TYPE)) {
                    nested = true;
                } else {
                    throw new MapperParsingException("Trying to parse an object but has a different type [" + type + "] for [" + name + "]");
                }
            }
            fieldNode = node.get("include_in_parent");
            if (fieldNode != null) {
                nestedIncludeInParent = nodeBooleanValue(fieldNode);
                node.remove("include_in_parent");
            }
            fieldNode = node.get("include_in_root");
            if (fieldNode != null) {
                nestedIncludeInRoot = nodeBooleanValue(fieldNode);
                node.remove("include_in_root");
            }
            if (nested) {
                builder.nested = Nested.newNested(nestedIncludeInParent, nestedIncludeInRoot);
            }

        }

        protected static void parseProperties(ObjectMapper.Builder objBuilder, Map<String, Object> propsNode, ParserContext parserContext) {
            Iterator<Map.Entry<String, Object>> iterator = propsNode.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Object> entry = iterator.next();
                String fieldName = entry.getKey();
                if (fieldName.contains(".")) {
                    throw new MapperParsingException("Field name [" + fieldName + "] cannot contain '.'");
                }
                // Should accept empty arrays, as a work around for when the
                // user can't provide an empty Map. (PHP for example)
                boolean isEmptyList = entry.getValue() instanceof List && ((List<?>) entry.getValue()).isEmpty();

                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> propNode = (Map<String, Object>) entry.getValue();
                    String type;
                    Object typeNode = propNode.get("type");
                    if (typeNode != null) {
                        type = typeNode.toString();
                    } else {
                        // lets see if we can derive this...
                        if (propNode.get("properties") != null) {
                            type = ObjectMapper.CONTENT_TYPE;
                        } else if (propNode.size() == 1 && propNode.get("enabled") != null) {
                            // if there is a single property with the enabled
                            // flag on it, make it an object
                            // (usually, setting enabled to false to not index
                            // any type, including core values, which
                            type = ObjectMapper.CONTENT_TYPE;
                        } else {
                            throw new MapperParsingException("No type specified for field [" + fieldName + "]");
                        }
                    }

                    Mapper.TypeParser typeParser = parserContext.typeParser(type);
                    if (typeParser == null) {
                        throw new MapperParsingException("No handler for type [" + type + "] declared on field [" + fieldName + "]");
                    }
                    objBuilder.add(typeParser.parse(fieldName, propNode, parserContext));
                    propNode.remove("type");
                    DocumentMapperParser.checkNoRemainingFields(fieldName, propNode, parserContext.indexVersionCreated());
                    iterator.remove();
                } else if (isEmptyList) {
                    iterator.remove();
                } else {
                    throw new MapperParsingException("Expected map for property [fields] on field [" + fieldName + "] but got a "
                            + fieldName.getClass());
                }
            }

            DocumentMapperParser.checkNoRemainingFields(propsNode, parserContext.indexVersionCreated(),
                    "DocType mapping definition has unsupported parameters: ");

        }

        protected Builder createBuilder(String name) {
            return object(name);
        }
    }

    private final String fullPath;

    private final boolean enabled;

    private final Nested nested;

    private final CqlCollection cqlCollection;
    private final CqlStruct cqlStruct;
    private final boolean cqlPartialUpdate;
    private final boolean cqlPartitionKey;
    private final boolean cqlStaticColumn;
    private final int cqlPrimaryKeyOrder;
    
    private final String nestedTypePathAsString;
    private final BytesRef nestedTypePathAsBytes;

    private final Filter nestedTypeFilter;

    private volatile Dynamic dynamic;

    private final ContentPath.Type pathType;

    private Boolean includeInAll;

    private volatile CopyOnWriteHashMap<String, Mapper> mappers;

    ObjectMapper(String name, String fullPath, CqlCollection cqlCollection, CqlStruct cqlStruct, boolean cqlPartialUpdate, boolean cqlPartitionKey, int cqlPrimaryKeyOrder, boolean cqlStaticColumn, boolean enabled, Nested nested, Dynamic dynamic, ContentPath.Type pathType, Map<String, Mapper> mappers) {
        super(name);
        this.fullPath = fullPath;
        this.cqlCollection = cqlCollection;
        this.cqlStruct = cqlStruct;
        this.cqlPartialUpdate = cqlPartialUpdate;
        this.cqlPartitionKey = cqlPartitionKey;
        this.cqlStaticColumn = cqlStaticColumn;
        this.cqlPrimaryKeyOrder = cqlPrimaryKeyOrder;
        this.enabled = enabled;
        this.nested = nested;
        this.dynamic = dynamic;
        this.pathType = pathType;
        if (mappers == null) {
            this.mappers = new CopyOnWriteHashMap<>();
        } else {
            this.mappers = CopyOnWriteHashMap.copyOf(mappers);
        }
        this.nestedTypePathAsString = "__" + fullPath;
        this.nestedTypePathAsBytes = new BytesRef(nestedTypePathAsString);
        this.nestedTypeFilter = new QueryWrapperFilter(new TermQuery(new Term(TypeFieldMapper.NAME, nestedTypePathAsBytes)));
    }

    @Override
    protected ObjectMapper clone() {
        ObjectMapper clone;
        try {
            clone = (ObjectMapper) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException();
        }
        return clone;
    }

    /**
     * Build a mapping update with the provided sub mapping update.
     */
    public ObjectMapper mappingUpdate(Mapper mapper) {
        ObjectMapper mappingUpdate = clone();
        // reset the sub mappers
        mappingUpdate.mappers = new CopyOnWriteHashMap<>();
        mappingUpdate.putMapper(mapper);
        return mappingUpdate;
    }

    @Override
    public String name() {
        return this.fullPath;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public ContentPath.Type pathType() {
        return pathType;
    }

    public Mapper getMapper(String field) {
        return mappers.get(field);
    }

    @Override
    public void includeInAll(Boolean includeInAll) {
        if (includeInAll == null) {
            return;
        }
        this.includeInAll = includeInAll;
        // when called from outside, apply this on all the inner mappers
        for (Mapper mapper : mappers.values()) {
            if (mapper instanceof AllFieldMapper.IncludeInAll) {
                ((AllFieldMapper.IncludeInAll) mapper).includeInAll(includeInAll);
            }
        }
    }

    @Override
    public void includeInAllIfNotSet(Boolean includeInAll) {
        if (this.includeInAll == null) {
            this.includeInAll = includeInAll;
        }
        // when called from outside, apply this on all the inner mappers
        for (Mapper mapper : mappers.values()) {
            if (mapper instanceof AllFieldMapper.IncludeInAll) {
                ((AllFieldMapper.IncludeInAll) mapper).includeInAllIfNotSet(includeInAll);
            }
        }
    }

    @Override
    public void unsetIncludeInAll() {
        includeInAll = null;
        // when called from outside, apply this on all the inner mappers
        for (Mapper mapper : mappers.values()) {
            if (mapper instanceof AllFieldMapper.IncludeInAll) {
                ((AllFieldMapper.IncludeInAll) mapper).unsetIncludeInAll();
            }
        }
    }

    public Nested nested() {
        return this.nested;
    }

    public Filter nestedTypeFilter() {
        return this.nestedTypeFilter;
    }

    /**
     * Put a new mapper.
     * NOTE: this method must be called under the current {@link DocumentMapper}
     * lock if concurrent updates are expected.
     */
    public void putMapper(Mapper mapper) {
        if (mapper instanceof AllFieldMapper.IncludeInAll) {
            ((AllFieldMapper.IncludeInAll) mapper).includeInAllIfNotSet(includeInAll);
        }
        mappers = mappers.copyAndPut(mapper.simpleName(), mapper);
    }

    @Override
    public Iterator<Mapper> iterator() {
        return mappers.values().iterator();
    }

    public String fullPath() {
        return this.fullPath;
    }

    public String nestedTypePathAsString() {
        return nestedTypePathAsString;
    }

    public final Dynamic dynamic() {
        return dynamic;
    }

    @Override
    public void merge(final Mapper mergeWith, final MergeResult mergeResult) throws MergeMappingException {
        if (!(mergeWith instanceof ObjectMapper)) {
            mergeResult.addConflict("Can't merge a non object mapping [" + mergeWith.name() + "] with an object mapping [" + name() + "]");
            return;
        }
        ObjectMapper mergeWithObject = (ObjectMapper) mergeWith;

        if (nested().isNested()) {
            if (!mergeWithObject.nested().isNested()) {
                mergeResult.addConflict("object mapping [" + name() + "] can't be changed from nested to non-nested");
                return;
            }
        } else {
            if (mergeWithObject.nested().isNested()) {
                mergeResult.addConflict("object mapping [" + name() + "] can't be changed from non-nested to nested");
                return;
            }
        }

        if (!mergeResult.simulate()) {
            if (mergeWithObject.dynamic != null) {
                this.dynamic = mergeWithObject.dynamic;
            }
        }

        doMerge(mergeWithObject, mergeResult);

        List<Mapper> mappersToPut = new ArrayList<>();
        List<ObjectMapper> newObjectMappers = new ArrayList<>();
        List<FieldMapper> newFieldMappers = new ArrayList<>();
        for (Mapper mapper : mergeWithObject) {
            Mapper mergeWithMapper = mapper;
            Mapper mergeIntoMapper = mappers.get(mergeWithMapper.simpleName());
            if (mergeIntoMapper == null) {
                // no mapping, simply add it if not simulating
                if (!mergeResult.simulate()) {
                    mappersToPut.add(mergeWithMapper);
                    MapperUtils.collect(mergeWithMapper, newObjectMappers, newFieldMappers);
                }
            } else if (mergeIntoMapper instanceof MetadataFieldMapper == false) {
                // root mappers can only exist here for backcompat, and are merged in Mapping
                mergeIntoMapper.merge(mergeWithMapper, mergeResult);
            }
        }
        if (!newFieldMappers.isEmpty()) {
            mergeResult.addFieldMappers(newFieldMappers);
        }
        if (!newObjectMappers.isEmpty()) {
            mergeResult.addObjectMappers(newObjectMappers);
        }
        // add the mappers only after the administration have been done, so it will not be visible to parser (which first try to read with no lock)
        for (Mapper mapper : mappersToPut) {
            putMapper(mapper);
        }
    }

    protected void doMerge(ObjectMapper mergeWith, MergeResult mergeResult) {

    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        toXContent(builder, params, null);
        return builder;
    }

    public void toXContent(XContentBuilder builder, Params params, ToXContent custom) throws IOException {
        builder.startObject(simpleName());
        if (nested.isNested()) {
            builder.field("type", NESTED_CONTENT_TYPE);
            if (nested.isIncludeInParent()) {
                builder.field("include_in_parent", true);
            }
            if (nested.isIncludeInRoot()) {
                builder.field("include_in_root", true);
            }
            
            if (cqlStruct != Defaults.CQL_STRUCT) {
                if (cqlStruct.equals(CqlStruct.MAP)) {
                    builder.field(TypeParsers.CQL_STRUCT, "map");
                } else if (cqlStruct.equals(CqlStruct.UDT)) {
                    builder.field(TypeParsers.CQL_STRUCT, "udt");
                } else if (cqlStruct.equals(CqlStruct.TUPLE)) {
                    builder.field(TypeParsers.CQL_STRUCT, "tuple");
                }
            }
        } else if (mappers.isEmpty() && custom == null) { // only write the object content type if there are no properties, otherwise, it is automatically detected
            builder.field("type", CONTENT_TYPE);
        }
        
        if (cqlCollection != Defaults.CQL_COLLECTION) {
            if (cqlCollection.equals(CqlCollection.SET)) {
                builder.field(TypeParsers.CQL_COLLECTION, "set");
            } else if (cqlCollection.equals(CqlCollection.LIST)) {
                builder.field(TypeParsers.CQL_COLLECTION, "list");
            } else if (cqlCollection.equals(CqlCollection.SINGLETON)) {
                builder.field(TypeParsers.CQL_COLLECTION, "singleton");
            }
        }
        if (cqlPartialUpdate != Defaults.CQL_MANDATORY) {
            builder.field(TypeParsers.CQL_MANDATORY, cqlPartialUpdate);
        }
        
        if (cqlPartitionKey != Defaults.CQL_PARTITION_KEY) {
            builder.field(TypeParsers.CQL_PARTITION_KEY, cqlPartitionKey);
        }
        
        if (cqlPrimaryKeyOrder != Defaults.CQL_PRIMARY_KEY_ORDER) {
            builder.field(TypeParsers.CQL_PRIMARY_KEY_ORDER, cqlPrimaryKeyOrder);
        }
        
        if (cqlStaticColumn != Defaults.CQL_STATIC_COLUMN) {
            builder.field(TypeParsers.CQL_STATIC_COLUMN, cqlStaticColumn);
        }
        
        if (dynamic != null) {
            builder.field("dynamic", dynamic.name().toLowerCase(Locale.ROOT));
        }
        if (enabled != Defaults.ENABLED) {
            builder.field("enabled", enabled);
        }
        if (pathType != Defaults.PATH_TYPE) {
            builder.field("path", pathType.name().toLowerCase(Locale.ROOT));
        }
        if (includeInAll != null) {
            builder.field("include_in_all", includeInAll);
        }

        if (custom != null) {
            custom.toXContent(builder, params);
        }

        doXContent(builder, params);

        // sort the mappers so we get consistent serialization format
        Mapper[] sortedMappers = Iterables.toArray(mappers.values(), Mapper.class);
        Arrays.sort(sortedMappers, new Comparator<Mapper>() {
            @Override
            public int compare(Mapper o1, Mapper o2) {
                return o1.name().compareTo(o2.name());
            }
        });

        int count = 0;
        for (Mapper mapper : sortedMappers) {
            if (!(mapper instanceof MetadataFieldMapper)) {
                if (count++ == 0) {
                    builder.startObject("properties");
                }
                mapper.toXContent(builder, params);
            }
        }
        if (count > 0) {
            builder.endObject();
        }
        builder.endObject();
    }

    protected void doXContent(XContentBuilder builder, Params params) throws IOException {

    }

    public CqlCollection cqlCollection() {
        return this.cqlCollection;
    }
    

    public String cqlCollectionTag() {
        if (this.cqlCollection.equals(CqlCollection.LIST)) return "list";
        if (this.cqlCollection.equals(CqlCollection.SET)) return "set";
        return "";
    }
    

    public CqlStruct cqlStruct() {
        return this.cqlStruct;
    }

    public boolean cqlPartialUpdate() {
        return this.cqlPartialUpdate;
    }

    @Override
    public boolean cqlPartitionKey() {
        return this.cqlPartitionKey;
    }

    @Override
    public int cqlPrimaryKeyOrder() {
        return this.cqlPrimaryKeyOrder;
    }

    @Override
    public boolean cqlStaticColumn() {
        return this.cqlStaticColumn;
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.iceberg.sink;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.base.StringSerializer;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.RowData.FieldGetter;
import org.apache.flink.table.data.util.RowDataUtil;
import org.apache.flink.table.runtime.operators.TableStreamOperator;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.runtime.util.StreamRecordCollector;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.Schema;
import org.apache.iceberg.flink.FlinkSchemaUtil;
import org.apache.iceberg.flink.RowDataWrapper;
import org.apache.iceberg.types.Types.NestedField;
import org.apache.inlong.sort.iceberg.sink.collections.RocksDbDiskMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This Operator has two functional above:
 * 1. Aggregating calculation in advance, reduce downstream computational workload
 * 2. Clustering data according to partition, reduce memory pressure caused by opening multiple writers downstream
 */
public class IcebergMiniBatchGroupOperator extends TableStreamOperator<RowData>
        implements
            OneInputStreamOperator<RowData, RowData>,
            BoundedOneInput {

    private static final long serialVersionUID = 9042068324817807379L;

    private static final Logger LOG = LoggerFactory.getLogger(IcebergMiniBatchGroupOperator.class);

    private transient StreamRecordCollector<RowData> collector;
    private transient RowDataWrapper wrapper;
    private transient RocksDbDiskMap<Tuple2<String, RowData>, RowData> spillableBuffer; // it's just a buffer
    private transient Set<String> allPartition;

    private final FieldGetter[] fieldsGetter;
    private final int[] equalityFieldIndex; // the position ordered of equality field in row schema
    private final PartitionKey partitionKey; // partition key helper
    private final Schema deleteScheam; // the equality field schema
    private final Schema rowSchema; // the whole field schema

    /**
     * Initialize field index.
     *
     * @param fieldsGetter function to get object from {@link RowData}
     * @param deleteSchema equality fields schema
     * @param rowSchema row data schema
     * @param partitionKey partition key
     */
    public IcebergMiniBatchGroupOperator(
            FieldGetter[] fieldsGetter,
            Schema deleteSchema,
            Schema rowSchema,
            PartitionKey partitionKey) {
        this.fieldsGetter = fieldsGetter;
        // note: here because `NestedField` does not override equals function, so can not indexOf by `NestedField`
        this.equalityFieldIndex = deleteSchema.columns().stream()
                .map(field -> rowSchema.columns()
                        .stream()
                        .map(NestedField::fieldId)
                        .collect(Collectors.toList())
                        .indexOf(field.fieldId()))
                .sorted()
                .mapToInt(Integer::valueOf)
                .toArray();
        this.partitionKey = partitionKey;
        this.deleteScheam = deleteSchema;
        this.rowSchema = rowSchema;
        // do some check, check whether index is legal. can not be null and unique, and number in fields range.
    }

    @Override
    public void open() throws Exception {
        super.open();
        LOG.info("Opening IcebergMiniBatchGroupOperator");

        this.collector = new StreamRecordCollector<>(output);
        this.wrapper = new RowDataWrapper(FlinkSchemaUtil.convert(rowSchema), rowSchema.asStruct());

        TypeInformation<Tuple2<String, RowData>> keyTypeInfo = new TupleTypeInfo(
                BasicTypeInfo.STRING_TYPE_INFO,
                InternalTypeInfo.of(FlinkSchemaUtil.convert(deleteScheam)));
        TypeInformation<RowData> valueTypeInfo = InternalTypeInfo.of(FlinkSchemaUtil.convert(rowSchema));
        MapStateDescriptor<Tuple2<String, RowData>, RowData> mapStateDescriptor =
                new MapStateDescriptor<>("bufferState", keyTypeInfo, valueTypeInfo);
        mapStateDescriptor.initializeSerializerUnlessSet(new ExecutionConfig()); // 后面只传入Serizalizer,不用传Descriptor

        this.spillableBuffer = new RocksDbDiskMap<>(mapStateDescriptor,
                System.getProperty("java.io.tmpdir") + "/mini_batch");
        this.allPartition = new HashSet<>();
    }

    @Override
    public void processElement(StreamRecord<RowData> element) throws Exception {
        RowData row = element.getValue();
        RowData primaryKey = GenericRowData.of(Arrays.stream(equalityFieldIndex)
                .boxed()
                .map(index -> fieldsGetter[index].getFieldOrNull(row))
                .toArray(Object[]::new));
        partitionKey.partition(wrapper.wrap(row));
        allPartition.add(partitionKey.toPath());

        if (RowDataUtil.isAccumulateMsg(row)) {
            spillableBuffer.put(new Tuple2<>(partitionKey.toPath(), primaryKey), row);
        } else {
            spillableBuffer.remove(new Tuple2<>(partitionKey.toPath(), primaryKey));
        }
    }

    @Override
    public void prepareSnapshotPreBarrier(long checkpointId) throws Exception {
        super.prepareSnapshotPreBarrier(checkpointId);
        flush();
    }

    @Override
    public void close() throws Exception {
        super.close();
        flush();
    }

    @Override
    public void endInput() throws Exception {
        flush();
    }

    private void flush() throws Exception {
        LOG.info("Flushing IcebergMiniBatchGroupOperator.");
        // Emit the rows group by partition
        // scan range key, this range key contains all one partition data
        DataOutputSerializer outputBuffer = new DataOutputSerializer(4096);
        for (String partition : allPartition) {
            StringSerializer.INSTANCE.serialize(partition, outputBuffer);
            spillableBuffer.scan(outputBuffer.getCopyOfBuffer())
                    .forEach(tuple -> collector.collect(tuple.f1));
            outputBuffer.clear();
        }
        spillableBuffer.clear();
        allPartition.clear();
    }
}

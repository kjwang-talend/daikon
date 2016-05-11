package org.talend.daikon.avro.util;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.talend.daikon.avro.AvroConverter;
import org.talend.daikon.avro.SchemaConstants;

import java.util.Date;

public class ConvertDate implements AvroConverter<Date, Long> {

    @Override
    public Schema getSchema() {
        return SchemaBuilder.builder().longBuilder().prop(SchemaConstants.JAVA_CLASS_FLAG, getDatumClass().getCanonicalName())
                .endLong();
    }

    @Override
    public Class<Date> getDatumClass() {
        return Date.class;
    }

    @Override
    public Date convertToDatum(Long value) {
        return value == null ? null : new Date(value);
    }

    @Override
    public Long convertToAvro(Date value) {
        return value == null ? null : value.getTime();
    }

}
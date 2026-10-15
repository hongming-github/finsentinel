package com.finsentinel.stream;

import com.finsentinel.common.AccountBaseline;
import com.finsentinel.common.Alert;
import com.finsentinel.common.Transaction;
import com.finsentinel.stream.rules.AccountRiskState;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.api.java.typeutils.PojoTypeInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against silent Kryo fallback: if a field type Flink cannot handle natively sneaks into a type
 * that crosses operators or lives in state, serialization gets slower and schema evolution breaks.
 */
class SerializationTest {

    @ParameterizedTest
    @ValueSource(classes = {Transaction.class, Alert.class, AccountBaseline.class, AccountRiskState.class,
            RawMessage.class, TracedTransaction.class, TracedAlert.class})
    void typeIsAFlinkPojoWithoutGenericFields(Class<?> type) {
        TypeInformation<?> info = TypeInformation.of(type);

        assertThat(info).isInstanceOf(PojoTypeInfo.class);
        PojoTypeInfo<?> pojo = (PojoTypeInfo<?>) info;
        for (int i = 0; i < pojo.getArity(); i++) {
            assertThat(pojo.getTypeAt(i))
                    .as("field %s of %s", pojo.getPojoFieldAt(i).getField().getName(), type.getSimpleName())
                    .isNotInstanceOf(GenericTypeInfo.class);
        }
    }
}

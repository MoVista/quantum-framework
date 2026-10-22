package com.e2eq.framework.imports;

import com.e2eq.framework.imports.spi.ImportContext;
import com.e2eq.framework.imports.spi.PreValidationTransformer;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.test.CSVModel;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class ReviewRowMaskTransformer implements PreValidationTransformer {
    @Override
    public String getName() { return "pr33-row-mask"; }

    @Override
    public TransformResult transform(BaseModel bean, Map<String, Object> rowData, ImportContext context) {
        if (bean instanceof CSVModel csv && "one".equals(rowData.get("testField3"))) {
            csv.setNumberField(0);
        }
        return TransformResult.success();
    }
}

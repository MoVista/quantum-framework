package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.test.UnversionedTestModel;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TestUnversionedRepo extends MorphiaRepo<UnversionedTestModel> {
}

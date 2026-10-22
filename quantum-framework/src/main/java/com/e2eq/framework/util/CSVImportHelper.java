package com.e2eq.framework.csv;


import com.e2eq.framework.imports.dynamic.DynamicAttributeImportService;
import com.e2eq.framework.imports.service.ImportProfileService;
import com.e2eq.framework.imports.spi.BatchLifecycleHandler;
import com.e2eq.framework.imports.spi.ImportBatch;
import com.e2eq.framework.imports.spi.ImportContext;
import com.e2eq.framework.imports.spi.PreValidationTransformer;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import com.e2eq.framework.model.persistent.base.DynamicAttributeSupport;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.imports.ImportIntent;
import com.e2eq.framework.model.persistent.imports.ImportProfile;
import com.e2eq.framework.model.persistent.imports.ParsedHeader;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.ImportSessionRepo;
import com.e2eq.framework.model.persistent.morphia.RefNameMatching;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import java.nio.file.Files;
import java.nio.file.Path;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.ws.rs.WebApplicationException;

import lombok.Getter;
import lombok.Setter;
import org.supercsv.cellprocessor.*;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.exception.SuperCsvException;
import org.supercsv.io.dozer.CsvDozerBeanReader;
import org.supercsv.io.dozer.ICsvDozerBeanReader;
import org.supercsv.prefs.CsvPreference;
import org.supercsv.quote.AlwaysQuoteMode;
import org.supercsv.quote.NormalQuoteMode;
import org.supercsv.quote.QuoteMode;
import org.supercsv.util.CsvContext;
import org.bson.types.ObjectId;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.IdentityHashMap;

import static java.lang.String.format;

@ApplicationScoped
public class CSVImportHelper {
    private static final int BATCH_SIZE = 1000;
    private static final int PREVIEW_LIMIT = 100;
    // Fallback in-memory session store for environments without CDI injection (e.g., unit tests)
    private static final java.util.Map<String, ImportSession<?>> MEMORY_SESSIONS = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject
    Validator validator;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ImportSessionRepo importSessionRepo;

    @Inject
    com.e2eq.framework.model.persistent.morphia.ImportSessionRowRepo importSessionRowRepo;

    @Inject
    io.quarkus.security.identity.SecurityIdentity securityIdentity;

    @Inject
    ImportProfileService importProfileService;

    @Inject
    DynamicAttributeImportService dynamicAttributeImportService;

    private volatile ImportRecordMerger recordMerger;

    public <T> void validateBean(T bean) {
        Set<ConstraintViolation<T>> violations = validator.validate(bean);

        if (!violations.isEmpty()) {

            // create a message from the violations:
            StringBuilder messageBuffer = new StringBuilder();
            for (ConstraintViolation<T> violation : violations) {
                messageBuffer.append(violation.getMessage()).append("\n");
            }

            throw new ValidationException(messageBuffer.toString().trim());
        }
    }

    private QuoteMode getQuoteMode(String quotingStrategy) {
        if ("QUOTE_ALL_COLUMNS".equalsIgnoreCase(quotingStrategy)) {
            return new AlwaysQuoteMode();
        } else if ("QUOTE_WHERE_ESSENTIAL".equalsIgnoreCase(quotingStrategy)) {
            return new NormalQuoteMode();
        } else {
            throw new ValidationException(format("The value %s is not one of the supported quote strategies: %s or %s",
                    quotingStrategy, "QUOTE_ALL_COLUMNS", "QUOTE_WHERE_ESSENTIAL"));
        }
    }

    private String[] createFieldMapping(String[] header, List<String> requestedColumns, List<String> preferredColumnNames) {
        if (requestedColumns == null || requestedColumns.isEmpty()) {
            return header;
        }

        String[] fieldMapping = new String[requestedColumns.size()];
        for (int i = 0; i < requestedColumns.size(); i++) {
            String columnName = requestedColumns.get(i);
            if (preferredColumnNames != null && i < preferredColumnNames.size()) {
                String preferredName = preferredColumnNames.get(i);
                if (!preferredName.isEmpty()) {
                    columnName = preferredName;
                }
            }
            fieldMapping[i] = columnName;
        }
        return fieldMapping;
    }

    @FunctionalInterface
    public interface FailedRecordHandler<T> {
        void handleFailedRecord(T record);
    }

    @Getter
    public static class ImportResult<T> {
        // New: session and detailed metrics/results for preview and commit
        @Setter
        private String sessionId;
        private int totalRows;
        private int validRows;
        private int errorRows;
        @Setter
        private int updateCount;
        @Setter
        private int insertCount;
        private int importedCount;
        private int failedCount;
        private final List<String> failedRecordsFeedback;
        private final List<ImportRowResult<T>> rowResults = new ArrayList<>();

        public ImportResult(int importedCount, int failedCount) {
            this.importedCount = importedCount;
            this.failedCount = failedCount;
            this.failedRecordsFeedback = new ArrayList<>();
        }

        public void incrementTotalRows() {
            this.totalRows++;
        }

        public void incrementValidRows() {
            this.validRows++;
        }

        public void incrementErrorRows() {
            this.errorRows++;
        }

        public void incrementFailedCount() {
            incrementFailedCount(1);
        }

        public void incrementFailedCount(int amount) {
            this.failedCount = this.failedCount + amount;
        }

        public void incrementImportedCount() {
            incrementImportedCount(1);
        }

        public void incrementImportedCount(int amount) {
            this.importedCount = this.importedCount + amount;
        }

        public void addFailedRecordFeedback(String feedback) {
            this.failedRecordsFeedback.add(feedback);
        }

        /**
         * Moves a row that was counted as valid with the given intent to the error counts.
         *
         * @param intent the intent the row was counted under
         */
        public void reclassifyValidRowAsError(Intent intent) {
            this.validRows--;
            this.errorRows++;
            if (intent == Intent.INSERT) {
                this.insertCount--;
            } else if (intent == Intent.UPDATE) {
                this.updateCount--;
            }
        }

    }

    public enum FieldErrorCode {
        VALIDATION,
        PARSE,
        MAPPING,
        DB,
        DB_DOC_TOO_LARGE,
        DB_SCHEMA_VALIDATION
    }

    // Per-field error information
    @Setter
    @Getter
    public static class FieldError {
        private String field; // may be null if unknown
        private String message;
        private FieldErrorCode code; // e.g., VALIDATION, PARSE, MAPPING, DB

        public FieldError() {
        }

        public FieldError(String field, String message, FieldErrorCode code) {
            this.field = field;
            this.message = message;
            this.code = code;
        }

    }

    public enum Intent {INSERT, UPDATE, SKIP}

    // Per-row result
    @Getter
    public static class ImportRowResult<T> {
        @Setter
        private int rowNumber;
        @Setter
        private String refName;
        @Setter
        private Intent intent = Intent.SKIP;
        @Setter
        private String rawData;
        private final List<FieldError> errors = new ArrayList<>();
        @Setter
        private T record; // optional reference for debugging
        /** Per-row paths written by profile processing, including the supplied CSV columns. */
        @Setter
        @com.fasterxml.jackson.annotation.JsonIgnore
        private List<String> importedColumns;

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    // Minimal session holder for preview/commit flow
    @Getter
    public static class ImportSession<T> {
        private final String id;
        private final Class<T> type;
        private final List<ImportRowResult<T>> rows;
        @Setter
        private String status; // PENDING, READY, COMMITTED, CANCELED
        /** Property paths supplied by the file; see the persistent ImportSession.importedColumns. */
        @Setter
        private List<String> importedColumns;

        public ImportSession(String id, Class<T> type, List<ImportRowResult<T>> rows) {
            this.id = id;
            this.type = type;
            this.rows = rows;
            this.status = "READY";
        }

    }

    @Getter
    public static class CommitResult {
        private final int imported;
        private final int failed;

        public CommitResult(int imported, int failed) {
            this.imported = imported;
            this.failed = failed;
        }
    }

    private static String safeRaw(org.supercsv.io.ICsvReader r) {
        try {
            return r.getUntokenizedRow();
        } catch (Exception ignore) {
            return null;
        }
    }

    public <T extends UnversionedBaseModel> ImportResult<T> preProcessCSV(
            BaseMorphiaRepo<T> repo,
            InputStream inputStream,
            char fieldSeparator,
            char quoteChar,
            boolean skipHeaderRow,
            List<String> requestedColumns,
            Charset charset,
            boolean mustUseBOM,
            String quotingStrategy,
            FailedRecordHandler<T> failedRecordHandler
    ) throws IOException {
        ImportResult<T> result = new ImportResult<>(0, 0);
        try (Reader reader = new InputStreamReader(inputStream, charset)) {
            if (mustUseBOM) {
                reader.read(); // Skip BOM character
            }

            ICsvDozerBeanReader beanReader = new CsvDozerBeanReader(reader,
                    new CsvPreference.Builder(quoteChar, fieldSeparator, "\r\n")
                            .useQuoteMode(getQuoteMode(quotingStrategy))
                            .build());

            String[] header = beanReader.getHeader(true);
            if (skipHeaderRow && header == null) {
                throw new IllegalArgumentException("CSV file does not contain a header row");
            }

            //String[] fieldMapping = createFieldMapping(header, requestedColumns, preferredColumnNames);

            // convert requestedColumns to an array of strings
            String[] fieldMapping = requestedColumns.toArray(new String[requestedColumns.size()]);

            beanReader.configureBeanMapping(repo.getPersistentClass(), fieldMapping);

            ListCellProcessor listProcessor = new ListCellProcessor();
            CellProcessor[] processors = buildProcessors(repo.getPersistentClass(), requestedColumns, listProcessor);

            result = preprocessFlatProperty(repo, beanReader, processors, failedRecordHandler);


        } catch (IOException e) {
            throw new WebApplicationException("Error importing CSV: " + e.getMessage(), e, 400);
        }

        return result;
    }

    public static class ParseEnum<E extends Enum<E>> extends CellProcessorAdaptor {
        private final Class<E> enumType;

        public ParseEnum(Class<E> enumType) {
            this.enumType = enumType;
        }

        @Override
        public Object execute(Object value, CsvContext ctx) {
            if (value == null) return next.execute(null, ctx);
            String s = value.toString().trim();
            for (E e : enumType.getEnumConstants()) {
                if (e.name().equalsIgnoreCase(s)) {
                    return next.execute(e, ctx);
                }
            }
            throw new org.supercsv.exception.SuperCsvCellProcessorException(
                    "Invalid enum value '" + s + "' for " + enumType.getSimpleName(), ctx, this);
        }
    }

    private CellProcessor[] buildProcessors(Class<?> clazz, List<String> cols, ListCellProcessor listProcessor) {
        CellProcessor[] processors = new CellProcessor[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            String fieldName = cols.get(i);
            if (fieldName.contains("[")) {
                processors[i] = new org.supercsv.cellprocessor.Optional(listProcessor);
                continue;
            }
            Class<?> type = getFieldType(clazz, fieldName);
            if (type == int.class || type == Integer.class) {
                processors[i] = new org.supercsv.cellprocessor.Optional(new ParseInt());
            } else if (type == long.class || type == Long.class) {
                processors[i] = new org.supercsv.cellprocessor.Optional(new ParseLong());
            } else if (type == double.class || type == Double.class ||
                    type == float.class || type == Float.class) {
                processors[i] = new org.supercsv.cellprocessor.Optional(new ParseDouble());
            } else if (type == java.math.BigDecimal.class) {
                processors[i] = new org.supercsv.cellprocessor.Optional(new ParseBigDecimal());
            } else if (type != null && type.isEnum()) {
                @SuppressWarnings("unchecked")
                Class<? extends Enum<?>> e = (Class<? extends Enum<?>>) type;
                processors[i] = new Optional(new ParseEnum(e));  // << key change
            } else {
                processors[i] = new org.supercsv.cellprocessor.Optional();
            }
        }
        return processors;
    }

    private Class<?> getFieldType(Class<?> clazz, String name) {
        String clean = name.replace("[0]", "");
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(clean).getType();
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    public class ListCellProcessor extends CellProcessorAdaptor {

        protected int i;

        public Object execute(Object value, CsvContext context) {
            validateInputNotNull(value, context);

            if (value instanceof List) {
                List list = ((List) value);
                if (i >= list.size()) {
                    return next.execute("", context);
                } else {
                    return next.execute(((List) value).get(i), context);
                }

            } else {
                return next.execute(value, context);
            }
        }

        public void setIndex(int index) {
            this.i = index;
        }
    }

    private Reader makeReader(InputStream in, Charset cs, boolean mustUseBOM) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, cs));
        br.mark(1);
        int ch = br.read();
        if (!(mustUseBOM && ch == 0xFEFF)) br.reset(); // only consume if BOM was required and present
        return br;
    }

    public <T extends UnversionedBaseModel> ImportResult<T> importCSV(
            BaseMorphiaRepo<T> repo,
            InputStream inputStream,
            char fieldSeparator,
            char quoteChar,
            boolean skipHeaderRow,
            List<String> requestedColumns,
            Charset charset,
            boolean mustUseBOM,
            String quotingStrategy,
            FailedRecordHandler<T> failedRecordHandler) {

        ImportResult<T> result = new ImportResult<>(0, 0);

        try (Reader reader = makeReader(inputStream, charset, mustUseBOM)) {
            CsvPreference preference = new CsvPreference.Builder(quoteChar, fieldSeparator, "\r\n")
                    .useQuoteMode(getQuoteMode(quotingStrategy))
                    .build();
            ICsvDozerBeanReader beanReader = new CsvDozerBeanReader(reader, preference);

            String[] header = beanReader.getHeader(skipHeaderRow);
            if (skipHeaderRow && header == null) {
                throw new IllegalArgumentException("CSV file does not contain a header row");
            }

            final String[] fieldMapping = requestedColumns.toArray(new String[0]);
            beanReader.configureBeanMapping(repo.getPersistentClass(), fieldMapping);

            ListCellProcessor listProcessor = new ListCellProcessor();
            CellProcessor[] processors = buildProcessors(repo.getPersistentClass(), requestedColumns, listProcessor);

            result = importFlatProperty(repo, beanReader, preference, processors, failedRecordHandler, fieldMapping);
        } catch (IOException e) {
            throw new WebApplicationException("Error importing CSV: " + e.getMessage(), e, 400);
        }

        return result;
    }


    private <T extends UnversionedBaseModel> ImportResult<T> preprocessFlatProperty(BaseMorphiaRepo<T> repo,
                                                                                    ICsvDozerBeanReader beanReader,
                                                                                    CellProcessor[] processors,
                                                                                    FailedRecordHandler<T> failedRecordHandler) throws IOException {
        ImportResult<T> result = new ImportResult<>(0, 0);
        List<T> batch = new ArrayList<>(BATCH_SIZE);

        while (true) {
            T bean = beanReader.read(repo.getPersistentClass(), processors);
            if (bean == null) break;

            batch.add(bean);
            if (batch.size() >= BATCH_SIZE) {
                preProcessBatch(repo, batch, result);
                batch.clear();
            }
        }

        // Process any remaining entities in the buffer
        if (!batch.isEmpty()) {
            result = preProcessBatch(repo, batch, result);
        }

        return result;
    }

    private static String resolveFieldFromParseException(Exception ex, String[] fieldMapping) {
        if (ex instanceof SuperCsvException sce) {
            CsvContext ctx = sce.getCsvContext();
            if (ctx != null) {
                int col = ctx.getColumnNumber(); // 1-based
                if (col > 0 && col <= fieldMapping.length) {
                    return fieldMapping[col - 1];
                }
            }
        }
        return null; // fallback if not available
    }

    private <T extends UnversionedBaseModel> ImportResult<T> importFlatProperty(
            BaseMorphiaRepo<T> repo,
            ICsvDozerBeanReader beanReader,
            CsvPreference preference,
            CellProcessor[] processors,
            FailedRecordHandler<T> failedRecordHandler,
            String[] fieldMapping) throws IOException {

        ImportResult<T> result = new ImportResult<>(0, 0);
        List<String> importedColumns = List.of(fieldMapping);

        // Duplicate groups are known only at EOF. Spool the raw CSV rows instead of retaining the
        // whole object graph, then parse them again and save non-conflicting rows in bounded
        // batches. Re-parsing builds each record exactly as the first pass did; a JSON round trip
        // would drop @JsonIgnore values (a credential's password hash) and call setters that
        // reject the nulls of columns the file omits.
        DuplicateReferenceRows references = new DuplicateReferenceRows();
        List<Integer> spooledRows = new ArrayList<>();
        Path spool = Files.createTempFile("quantum-csv-", ".csv");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(spool, StandardCharsets.UTF_8)) {
                int rowNum = 1;
                while (true) {
                    T bean;
                    try {
                        bean = beanReader.read(repo.getPersistentClass(), processors);
                    } catch (Exception ex) {
                        ImportRowResult<T> rr = new ImportRowResult<>();
                        rr.setRowNumber(rowNum++);
                        rr.setRawData(safeRaw(beanReader));
                        rr.getErrors().add(new FieldError(resolveFieldFromParseException(ex, fieldMapping),
                                ex.getMessage(), FieldErrorCode.PARSE));
                        result.incrementTotalRows();
                        result.incrementErrorRows();
                        result.incrementFailedCount();
                        result.getRowResults().add(rr);
                        continue;
                    }
                    if (bean == null) break;
                    result.incrementTotalRows();
                    references.record(bean.getRefName(), rowNum);
                    spooledRows.add(rowNum++);
                    writer.write(beanReader.getUntokenizedRow());
                    writer.newLine();
                }
            }

            Map<Integer, String> conflicts = references.conflicts();
            List<T> batch = new ArrayList<>(BATCH_SIZE);
            Map<T, Integer> rowNums = new IdentityHashMap<>();
            Map<T, String> raws = new IdentityHashMap<>();
            try (ICsvDozerBeanReader replay = new CsvDozerBeanReader(
                    Files.newBufferedReader(spool, StandardCharsets.UTF_8), preference)) {
                replay.configureBeanMapping(repo.getPersistentClass(), fieldMapping);
                for (int rowNum : spooledRows) {
                    T bean;
                    String field = null;
                    String error = "The row could not be read again from the spooled file.";
                    try {
                        bean = replay.read(repo.getPersistentClass(), processors);
                    } catch (Exception ex) {
                        bean = null;
                        field = resolveFieldFromParseException(ex, fieldMapping);
                        error = ex.getMessage();
                    }
                    if (bean == null) {
                        // Parsed on the first pass, so not expected; earlier batches may already be
                        // saved, so report the row rather than failing the whole request.
                        ImportRowResult<T> rr = new ImportRowResult<>();
                        rr.setRowNumber(rowNum);
                        rr.getErrors().add(new FieldError(field, error, FieldErrorCode.PARSE));
                        result.incrementErrorRows();
                        result.incrementFailedCount();
                        result.getRowResults().add(rr);
                        continue;
                    }
                    rowNums.put(bean, rowNum);
                    raws.put(bean, safeRaw(replay));
                    String conflict = conflicts.get(rowNum);
                    if (conflict != null) {
                        rejectRow(bean, failedRecordHandler, result, rowNums, raws, FieldErrorCode.VALIDATION, conflict);
                        rowNums.remove(bean);
                        raws.remove(bean);
                        continue;
                    }
                    batch.add(bean);
                    if (batch.size() >= BATCH_SIZE) {
                        saveDirectBatch(repo, batch, failedRecordHandler, result, rowNums, raws, importedColumns);
                        batch.clear();
                        rowNums.clear();
                        raws.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                saveDirectBatch(repo, batch, failedRecordHandler, result, rowNums, raws, importedColumns);
            }
        } finally {
            Files.deleteIfExists(spool);
        }

        return result;
    }

    private <T extends UnversionedBaseModel> void saveDirectBatch(
            BaseMorphiaRepo<T> repo, List<T> batch, FailedRecordHandler<T> failedRecordHandler,
            ImportResult<T> result, Map<T, Integer> rowNums, Map<T, String> raws, List<String> columns) {
        int importedBefore = result.getImportedCount();
        processBatch(repo, batch, failedRecordHandler, result, rowNums, raws, columns);
        result.validRows += result.getImportedCount() - importedBefore;
    }

    public <T extends UnversionedBaseModel> ImportResult<T> preProcessBatch(
            BaseMorphiaRepo<T> repo,
            List<T> batch,
            ImportResult<T> result) {
        if (batch.isEmpty()) return result;

        // determine which records are updates and which ones are inserts
        Map<String, T> existing = findExistingByRefName(repo, batch);
        List<T> newEntities = batch.stream().filter(entity -> !exists(existing, entity)).toList();
        List<T> existingEntities = batch.stream().filter(entity -> exists(existing, entity)).toList();

        int validNew = 0;
        for (T model : newEntities) {
            try {
                validateBean(model);
                validNew++;
            } catch (ValidationException e) {
                result.incrementFailedCount();
                result.addFailedRecordFeedback("Validation failed for record: " + model.toString() + " due to " + e.getMessage());
            }
        }

        int validExisting = 0;
        for (T model : existingEntities) {
            try {
                validateBean(model);
                validExisting++;
            } catch (ValidationException e) {
                result.incrementFailedCount();
                result.addFailedRecordFeedback("Validation failed for record: " + model.toString() + " due to " + e.getMessage());
            }
        }

        // accumulate counts
        result.setInsertCount(result.getInsertCount() + validNew);
        result.setUpdateCount(result.getUpdateCount() + validExisting);

        return result;
    }

    /**
     * Saves a batch of imported records. A record whose reference already exists updates that
     * document: the imported columns are applied to the existing document (see
     * {@link ImportRecordMerger}), which is then saved through the repository's normal save path.
     * Other records are inserted. A failing batch is bisected so one bad record does not fail the
     * rest; a failing single record is reported as a row error.
     *
     * <p>Updating requires the list of imported columns. Without it (for example a session created
     * by a caller that did not record {@code importedColumns}) an UPDATE row is rejected with a row
     * error rather than guessing which properties the file supplied.</p>
     *
     * @param importedColumns property paths supplied by the file, or null if unknown
     */
    private <T extends UnversionedBaseModel> ImportResult<T> processBatch(
            BaseMorphiaRepo<T> repo,
            List<T> batch,
            FailedRecordHandler<T> failedRecordHandler,
            ImportResult<T> result,
            Map<T, Integer> rowNums,
            Map<T, String> raws,
            List<String> importedColumns) {

        return processBatch(repo, batch, failedRecordHandler, result, rowNums, raws, importedColumns, Map.of());
    }

    private <T extends UnversionedBaseModel> ImportResult<T> processBatch(
            BaseMorphiaRepo<T> repo,
            List<T> batch,
            FailedRecordHandler<T> failedRecordHandler,
            ImportResult<T> result,
            Map<T, Integer> rowNums,
            Map<T, String> raws,
            List<String> importedColumns, Map<T, List<String>> rowColumns) {

        if (batch.isEmpty()) return result;

        Map<String, T> existing = findExistingByRefName(repo, batch);
        List<T> saved = new ArrayList<>(batch.size());
        List<T> toSave = new ArrayList<>(batch.size());
        int batchInserts = 0, batchUpdates = 0;
        for (T bean : batch) {
            List<String> columns = rowColumns.getOrDefault(bean, importedColumns);
            boolean columnsKnown = columns != null && !columns.isEmpty();
            T current = bean.getRefName() == null ? null : existing.get(bean.getRefName());
            if (current != null && !columnsKnown) {
                rejectRow(bean, failedRecordHandler, result, rowNums, raws, FieldErrorCode.VALIDATION, format(
                        "Reference '%s' already exists, but this import does not record which columns it supplies, "
                                + "so the existing record cannot be updated safely.", bean.getRefName()));
                continue;
            }
            if (current == null && bean.getId() != null) {
                // Only an UPDATE may carry an identity; saving it as an insert would replace that document.
                rejectRow(bean, failedRecordHandler, result, rowNums, raws, FieldErrorCode.VALIDATION, format(
                        "Reference '%s' does not exist, but the row carries the id of another record.",
                        bean.getRefName()));
                continue;
            }
            try {
                // Merge onto the freshly loaded document rather than a JSON copy: the API mapper
                // omits persisted @JsonIgnore fields (for example a credential's password hash).
                T candidate = current == null ? bean : recordMerger().merge(current, bean, columns);
                validateBean(candidate);
                saved.add(bean);
                toSave.add(candidate);
                if (current == null) batchInserts++;
                else batchUpdates++;
            } catch (Exception e) {
                rejectRow(bean, failedRecordHandler, result, rowNums, raws, FieldErrorCode.VALIDATION, e.getMessage());
            }
        }
        if (toSave.isEmpty()) {
            return result;
        }

        List<T> unidentified = toSave.stream().filter(candidate -> candidate.getId() == null).toList();
        try {
            repo.save(toSave);

            // On success, reflect counts
            result.incrementImportedCount(toSave.size());
            result.setInsertCount(result.getInsertCount() + batchInserts);
            result.setUpdateCount(result.getUpdateCount() + batchUpdates);

        } catch (Exception e) {
            if (saved.size() == 1) {
                // Single-record failure: attach structured DB error
                T failedRecord = saved.get(0);

                // Classify DB error (duplicate key, etc.)
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();

                FieldErrorCode code = FieldErrorCode.DB;
                String message = root.getMessage();
                if (message != null) {
                    if (message.contains("E11000")) {
                        message = "Duplicate key violation (unique index).";
                    } else if (message.contains("Document too large")) {
                        code = FieldErrorCode.DB_DOC_TOO_LARGE;
                    } else if (message.contains("Validation failed")) {
                        code = FieldErrorCode.DB_SCHEMA_VALIDATION;
                    }
                }

                rejectRow(failedRecord, failedRecordHandler, result, rowNums, raws, code,
                        message != null ? message : "Database write failed.");

            } else {
                // A failed save may have assigned ids to new records; retry them as inserts.
                unidentified.forEach(candidate -> candidate.setId(null));
                // Bisect to isolate failing records without losing successes
                int midPoint = saved.size() / 2;
                List<T> firstHalf = new ArrayList<>(saved.subList(0, midPoint));
                List<T> secondHalf = new ArrayList<>(saved.subList(midPoint, saved.size()));

                processBatch(repo, firstHalf, failedRecordHandler, result, rowNums, raws, importedColumns, rowColumns);
                processBatch(repo, secondHalf, failedRecordHandler, result, rowNums, raws, importedColumns, rowColumns);
            }
        }

        return result;
    }

    /** Reports one imported record as a failed row. */
    private <T extends UnversionedBaseModel> void rejectRow(
            T record, FailedRecordHandler<T> failedRecordHandler, ImportResult<T> result,
            Map<T, Integer> rowNums, Map<T, String> raws, FieldErrorCode code, String message) {
        ImportRowResult<T> rr = new ImportRowResult<>();
        rr.setRowNumber(rowNums.getOrDefault(record, -1));
        rr.setRefName(record.getRefName());
        rr.setRawData(raws.get(record));
        rr.getErrors().add(new FieldError("refName", message, code));

        if (failedRecordHandler != null) failedRecordHandler.handleFailedRecord(record);
        result.incrementFailedCount();
        result.incrementErrorRows();
        result.getRowResults().add(rr);
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private <T> T mergedCopy(T existing, T incoming, List<String> columns) {
        T copy = (T) mapper().convertValue(existing, existing.getClass());
        return recordMerger().merge(copy, incoming, columns);
    }

    private ImportRecordMerger recordMerger() {
        ImportRecordMerger merger = recordMerger;
        if (merger == null) {
            merger = new ImportRecordMerger(objectMapper != null ? objectMapper : new ObjectMapper());
            recordMerger = merger;
        }
        return merger;
    }

    /**
     * Looks up the documents that already exist for a batch's references. The map compares keys
     * like the reference-identity unique index, so a row for {@code abc} finds an existing
     * {@code ABC}.
     */
    private <T extends UnversionedBaseModel> Map<String, T> findExistingByRefName(BaseMorphiaRepo<T> repo, List<T> beans) {
        Map<String, T> existing = RefNameMatching.newMap();
        List<String> refNames = beans.stream()
                .map(T::getRefName)
                .filter(refName -> refName != null && !refName.isBlank())
                .distinct()
                .toList();
        if (refNames.isEmpty()) {
            return existing;
        }
        String tenant = SecurityContext.getPrincipalContext()
                .map(PrincipalContext::getDataDomain).map(DataDomain::getTenantId).orElse(null);
        for (T found : repo.getListFromRefNames(refNames)) {
            if (found.getRefName() != null) {
                if (tenant != null && (found.getDataDomain() == null
                        || !tenant.equals(found.getDataDomain().getTenantId()))) {
                    continue;
                }
                T previous = existing.putIfAbsent(found.getRefName(), found);
                if (previous != null && !java.util.Objects.equals(previous.getId(), found.getId())) {
                    throw new IllegalStateException("Ambiguous reference '" + found.getRefName()
                            + "' in the importing tenant; reconcile duplicate records before importing");
                }
            }
        }
        return existing;
    }

    private static <T extends UnversionedBaseModel> boolean exists(Map<String, T> existing, T bean) {
        return bean.getRefName() != null && existing.containsKey(bean.getRefName());
    }

    // New: analyze (preview) the CSV without saving, and create a session
    public <T extends UnversionedBaseModel> ImportResult<T> analyzeCSV(
            BaseMorphiaRepo<T> repo,
            InputStream inputStream,
            char fieldSeparator,
            char quoteChar,
            boolean skipHeaderRow,
            List<String> requestedColumns,
            Charset charset,
            boolean mustUseBOM,
            String quotingStrategy) throws IOException {

        ImportResult<T> result = new ImportResult<>(0, 0);

        boolean memMode = (importSessionRepo == null || importSessionRowRepo == null);
        List<ImportRowResult<T>> memRows = memMode ? new ArrayList<>() : null;

        String sessionId = java.util.UUID.randomUUID().toString();
        result.setSessionId(sessionId);

        com.e2eq.framework.model.persistent.imports.ImportSession session =
                memMode ? null : openImportSession(sessionId, repo, null);
        DuplicateReferenceRows references = new DuplicateReferenceRows();

        try (Reader reader = makeReader(inputStream, charset, mustUseBOM)) {
            ICsvDozerBeanReader beanReader = new CsvDozerBeanReader(reader,
                    new CsvPreference.Builder(quoteChar, fieldSeparator, "\r\n")
                            .useQuoteMode(getQuoteMode(quotingStrategy))
                            .build());

            String[] header = beanReader.getHeader(skipHeaderRow);
            if (skipHeaderRow && header == null) {
                throw new IllegalArgumentException("CSV file does not contain a header row");
            }

            final String[] fieldMapping = requestedColumns.toArray(new String[0]);
            beanReader.configureBeanMapping(repo.getPersistentClass(), fieldMapping);

            ListCellProcessor listProcessor = new ListCellProcessor();
            CellProcessor[] processors = buildProcessors(repo.getPersistentClass(), requestedColumns, listProcessor);

            List<T> batchBeans = new ArrayList<>(BATCH_SIZE);
            List<Integer> batchRowNums = new ArrayList<>(BATCH_SIZE);
            Map<Integer, String> batchRawByRowNum = new java.util.HashMap<>(BATCH_SIZE);

            int rowNum = 1; // first data row after (optional) header
            while (true) {
                T bean;
                try {
                    bean = beanReader.read(repo.getPersistentClass(), processors);
                } catch (Exception ex) {
                    String field = ex instanceof SuperCsvException ? resolveFieldFromParseException(ex, fieldMapping) : null;

                    ImportRowResult<T> rr = new ImportRowResult<>();
                    rr.setRowNumber(rowNum);
                    rr.setIntent(Intent.SKIP);
                    rr.setRawData(safeRaw(beanReader));
                    rr.getErrors().add(new FieldError(field, ex.getMessage(), FieldErrorCode.PARSE));

                    persistOrStoreRow(memMode, sessionId, rr, memRows, result);
                    rowNum++;
                    continue;
                }

                if (bean == null) break; // EOF

                // capture raw for this row (to be attached during validation)
                batchRawByRowNum.put(rowNum, safeRaw(beanReader));
                batchBeans.add(bean);
                batchRowNums.add(rowNum);

                if (batchBeans.size() >= BATCH_SIZE) {
                    List<ImportRowResult<T>> annotated = new ArrayList<>();
                    annotateIntentsAndValidate(repo, batchBeans, batchRowNums, batchRawByRowNum, annotated, result, requestedColumns);
                    stageAnnotatedRows(memMode, sessionId, annotated, memRows, result, references);
                    batchBeans.clear();
                    batchRowNums.clear();
                    batchRawByRowNum.clear();
                }

                rowNum++;
            }

            if (!batchBeans.isEmpty()) {
                List<ImportRowResult<T>> annotated = new ArrayList<>();
                annotateIntentsAndValidate(repo, batchBeans, batchRowNums, batchRawByRowNum, annotated, result, requestedColumns);
                stageAnnotatedRows(memMode, sessionId, annotated, memRows, result, references);
            }
        } catch (IOException e) {
            throw new WebApplicationException("Error analyzing CSV: " + e.getMessage(), e, 400);
        }

        applyDuplicateReferenceErrors(references, memMode, sessionId, memRows, result);
        closeImportSession(memMode, session, sessionId, repo, memRows, result, requestedColumns);
        return result;
    }

    /**
     * Creates and saves the persistent session record for a preview/commit import.
     */
    private <T extends UnversionedBaseModel> com.e2eq.framework.model.persistent.imports.ImportSession openImportSession(
            String sessionId, BaseMorphiaRepo<T> repo, ImportProfile profile) {
        com.e2eq.framework.model.persistent.imports.ImportSession session =
                new com.e2eq.framework.model.persistent.imports.ImportSession();
        session.setRefName(sessionId);
        session.setDisplayName("Import Session " + sessionId);
        session.setTargetType(repo.getPersistentClass().getName());
        session.setStatus("OPEN");
        String currentUser = null;
        try {
            if (securityIdentity != null && securityIdentity.getPrincipal() != null) {
                currentUser = securityIdentity.getPrincipal().getName();
            }
        } catch (Exception ignored) {
        }
        if (currentUser == null || currentUser.isEmpty()) currentUser = "anonymous";
        session.setUserId(currentUser);
        session.setCollectionName(repo.getPersistentClass().getSimpleName());
        session.setStartedAt(java.time.Instant.now());
        if (profile != null) {
            session.setProfileRefName(profile.getRefName());
        }
        importSessionRepo.save(session);
        return session;
    }

    /**
     * Records the final counts and imported columns on the session (persistent or in-memory).
     */
    private <T extends UnversionedBaseModel> void closeImportSession(
            boolean memMode,
            com.e2eq.framework.model.persistent.imports.ImportSession session,
            String sessionId,
            BaseMorphiaRepo<T> repo,
            List<ImportRowResult<T>> memRows,
            ImportResult<T> result,
            List<String> importedColumns) {
        List<String> columns = importedColumns == null ? null : List.copyOf(importedColumns);
        if (!memMode && session != null) {
            session.setTotalRows(result.getTotalRows());
            session.setValidRows(result.getValidRows());
            session.setErrorRows(result.getErrorRows());
            session.setInsertCount(result.getInsertCount());
            session.setUpdateCount(result.getUpdateCount());
            session.setImportedColumns(columns);
            importSessionRepo.save(session);
        } else if (memMode) {
            ImportSession<T> memorySession = new ImportSession<>(sessionId, repo.getPersistentClass(), memRows);
            memorySession.setImportedColumns(columns);
            MEMORY_SESSIONS.put(sessionId, memorySession);
        }
    }

    /**
     * Rejects every row whose reference also appears in another of the given rows (compared like the
     * reference-identity unique index): each such row gets a row error naming all rows of its group.
     * For callers that analyze a whole file themselves before staging it.
     *
     * @param rows the analyzed rows of one file
     * @param <T> target record type
     * @return the number of rows rejected
     */
    public <T> int rejectRepeatedReferences(List<ImportRowResult<T>> rows) {
        DuplicateReferenceRows references = new DuplicateReferenceRows();
        rows.forEach(row -> references.record(row.getRefName(), row.getRowNumber()));
        Map<Integer, String> conflicts = references.conflicts();
        int rejected = 0;
        for (ImportRowResult<T> row : rows) {
            String conflict = conflicts.get(row.getRowNumber());
            if (conflict != null) {
                row.getErrors().add(new FieldError("refName", conflict, FieldErrorCode.VALIDATION));
                rejected++;
            }
        }
        return rejected;
    }

    /**
     * Stages analyzed rows and records their references for the per-file duplicate check.
     */
    private <T extends UnversionedBaseModel> void stageAnnotatedRows(
            boolean memMode, String sessionId, List<ImportRowResult<T>> annotated,
            List<ImportRowResult<T>> memRows, ImportResult<T> result, DuplicateReferenceRows references) {
        for (ImportRowResult<T> ar : annotated) {
            references.record(ar.getRefName(), ar.getRowNumber());
            persistOrStoreAnnotatedRow(memMode, sessionId, ar, memRows, result);
        }
    }

    /**
     * Rejects every staged row whose reference also appears in another row of the same file:
     * adds a row error, marks the staged row as failed so commit skips it, and moves it from the
     * valid to the error counts.
     */
    private <T extends UnversionedBaseModel> void applyDuplicateReferenceErrors(
            DuplicateReferenceRows references, boolean memMode, String sessionId,
            List<ImportRowResult<T>> memRows, ImportResult<T> result) {
        Map<Integer, String> conflicts = references.conflicts();
        if (conflicts.isEmpty()) {
            return;
        }
        // Preview rows and in-memory rows are the same objects as the staged results.
        Map<Integer, ImportRowResult<T>> rowsByNumber = new HashMap<>();
        result.getRowResults().forEach(rr -> rowsByNumber.putIfAbsent(rr.getRowNumber(), rr));
        if (memRows != null) {
            memRows.forEach(rr -> rowsByNumber.putIfAbsent(rr.getRowNumber(), rr));
        }

        for (Map.Entry<Integer, String> conflict : conflicts.entrySet()) {
            int rowNumber = conflict.getKey();
            FieldError error = new FieldError("refName", conflict.getValue(), FieldErrorCode.VALIDATION);
            ImportRowResult<T> rr = rowsByNumber.get(rowNumber);
            Boolean wasValid = null;
            Intent intent = null;
            if (rr != null) {
                wasValid = !rr.hasErrors();
                intent = rr.getIntent();
                rr.getErrors().add(error);
            }
            if (!memMode) {
                com.e2eq.framework.model.persistent.imports.ImportSessionRow row = importSessionRowRepo
                        .findByRefName(com.e2eq.framework.model.persistent.imports.ImportSessionRow.stagingRefName(sessionId, rowNumber))
                        .orElse(null);
                if (row != null) {
                    if (wasValid == null) {
                        wasValid = !row.isHasErrors();
                        intent = parseIntent(row.getIntent());
                    }
                    List<FieldError> errors = readErrors(row.getErrorsJson());
                    errors.add(error);
                    row.setHasErrors(true);
                    try {
                        row.setErrorsJson(objectMapper.writeValueAsString(errors));
                    } catch (Exception ignore) {
                    }
                    importSessionRowRepo.save(row);
                }
            }
            if (Boolean.TRUE.equals(wasValid)) {
                result.reclassifyValidRowAsError(intent);
            }
        }
    }

    private List<FieldError> readErrors(String errorsJson) {
        if (errorsJson == null || errorsJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(errorsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<FieldError>>() { }));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static Intent parseIntent(String intent) {
        try {
            return intent == null ? Intent.SKIP : Intent.valueOf(intent);
        } catch (IllegalArgumentException e) {
            return Intent.SKIP;
        }
    }


    private <T extends UnversionedBaseModel> void annotateIntentsAndValidate(
            BaseMorphiaRepo<T> repo,
            List<T> batchBeans,
            List<Integer> batchRowNums,
            Map<Integer, String> rawByRowNum,          // <<< added
            List<ImportRowResult<T>> outRows,
            ImportResult<T> aggregate, List<String> importedColumns) {

        Map<String, T> existing = findExistingByRefName(repo, batchBeans);

        for (int i = 0; i < batchBeans.size(); i++) {
            T bean = batchBeans.get(i);
            int rn = batchRowNums.get(i);

            ImportRowResult<T> rr = new ImportRowResult<>();
            rr.setRowNumber(rn);
            rr.setRefName(bean.getRefName());
            rr.setRecord(bean);
            rr.setIntent(exists(existing, bean) ? Intent.UPDATE : Intent.INSERT);

            // attach captured raw line (always; or gate this on errors if desired)
            String raw = rawByRowNum != null ? rawByRowNum.get(rn) : null;
            if (raw != null) {
                rr.setRawData(raw);
            }

            // validate
            try {
                T current = bean.getRefName() == null ? null : existing.get(bean.getRefName());
                Set<ConstraintViolation<T>> violations = validator.validate(current == null ? bean
                        : mergedCopy(current, bean, importedColumns));
                if (violations != null) {
                    for (ConstraintViolation<T> v : violations) {
                        rr.getErrors().add(new FieldError(
                                v.getPropertyPath() != null ? v.getPropertyPath().toString() : null,
                                v.getMessage(),
                                FieldErrorCode.VALIDATION));
                    }
                }
            } catch (Exception ex) {
                rr.getErrors().add(new FieldError(null, ex.getMessage(), FieldErrorCode.VALIDATION));
            }

            outRows.add(rr);
        }
    }


    // Minimal commit: save all valid rows for INSERT/UPDATE from a session
    @SuppressWarnings("unchecked")
    public <T extends UnversionedBaseModel> CommitResult commitImport(String sessionId, BaseMorphiaRepo<T> repo) {
        boolean memMode = (importSessionRepo == null || importSessionRowRepo == null);
        if (memMode) {
            ImportSession<T> session = (ImportSession<T>) MEMORY_SESSIONS.get(sessionId);
            if (session == null) {
                throw new ValidationException("Unknown import session: " + sessionId);
            }
            if ("COMPLETED".equalsIgnoreCase(session.getStatus()) || "COMMITTED".equalsIgnoreCase(session.getStatus())) {
                return new CommitResult(0, 0);
            }
            List<T> toSave = new ArrayList<>();
            Map<T, List<String>> rowColumns = new IdentityHashMap<>();
            for (ImportRowResult<T> rr : session.getRows()) {
                if (!rr.hasErrors() && (rr.getIntent() == Intent.INSERT || rr.getIntent() == Intent.UPDATE)) {
                    toSave.add(rr.getRecord());
                    if (rr.getImportedColumns() != null && session.getImportedColumns() != null) {
                        rowColumns.put(rr.getRecord(), rr.getImportedColumns());
                    }
                }
            }
            ImportResult<T> tmp = new ImportResult<>(0, 0);
            processBatch(repo, toSave, rec -> {
            }, tmp, new IdentityHashMap<>(), new IdentityHashMap<>(), session.getImportedColumns(), rowColumns);
            logFailedRows(sessionId, tmp);
            session.setStatus("COMMITTED");
            return new CommitResult(tmp.getImportedCount(), tmp.getFailedCount());
        }
        com.e2eq.framework.model.persistent.imports.ImportSession session = importSessionRepo.findByRefName(sessionId)
                .orElseThrow(() -> new ValidationException("Unknown import session: " + sessionId));
        String st = session.getStatus();
        if ("COMPLETED".equalsIgnoreCase(st) || "COMMITTED".equalsIgnoreCase(st)) {
            return new CommitResult(0, 0);
        }
        if (!repo.getPersistentClass().getName().equals(session.getTargetType())) {
            throw new ValidationException(
                    "Session target type does not match repository persistent class"
            );
        }
        ImportResult<T> tmp = new ImportResult<>(0, 0);
        int skip = 0;
        int pageSize = BATCH_SIZE;
        while (true) {
            java.util.List<dev.morphia.query.filters.Filter> filters = new java.util.ArrayList<>();
            filters.add(dev.morphia.query.filters.Filters.eq("sessionRefName", sessionId));
            filters.add(dev.morphia.query.filters.Filters.eq("hasErrors", false));
            filters.add(dev.morphia.query.filters.Filters.in("intent", java.util.List.of(Intent.INSERT.name(), Intent.UPDATE.name())));
            java.util.List<com.e2eq.framework.model.persistent.imports.ImportSessionRow> page = importSessionRowRepo.getList(skip, pageSize, filters, null);
            if (page == null || page.isEmpty()) break;
            List<T> toSave = new ArrayList<>(page.size());
            Map<T, Integer> rowNums = new IdentityHashMap<>();
            Map<T, List<String>> rowColumns = new IdentityHashMap<>();
            for (com.e2eq.framework.model.persistent.imports.ImportSessionRow r : page) {
                if (r.getRecordJson() == null) {
                    tmp.incrementFailedCount();
                    continue;
                }
                try {
                    T bean = objectMapper.readValue(r.getRecordJson(), repo.getPersistentClass());
                    toSave.add(bean);
                    rowNums.put(bean, r.getRowNumber());
                    if (r.getImportedColumns() != null && session.getImportedColumns() != null) {
                        rowColumns.put(bean, r.getImportedColumns());
                    }
                } catch (Exception ex) {
                    // the staged record can no longer be read as the target type
                    tmp.incrementFailedCount();
                }
            }
            if (!toSave.isEmpty()) {
                processBatch(repo, toSave, rec -> {
                }, tmp, rowNums, new IdentityHashMap<>(), session.getImportedColumns(), rowColumns);
            }
            skip += page.size();
        }
        logFailedRows(sessionId, tmp);
        session.setStatus("COMPLETED");
        importSessionRepo.save(session);
        return new CommitResult(tmp.getImportedCount(), tmp.getFailedCount());
    }

    /** Logs why each row of a commit failed; the commit result itself only carries counts. */
    private static <T> void logFailedRows(String sessionId, ImportResult<T> result) {
        for (ImportRowResult<T> row : result.getRowResults()) {
            if (row.hasErrors()) {
                io.quarkus.logging.Log.warnf("Import session %s: row %d (%s) was not committed: %s", sessionId,
                        row.getRowNumber(), row.getRefName(),
                        row.getErrors().stream().map(FieldError::getMessage).collect(java.util.stream.Collectors.joining("; ")));
            }
        }
    }

    public void cancelImport(String sessionId) {
        boolean memMode = (importSessionRepo == null || importSessionRowRepo == null);
        if (memMode) {
            ImportSession<?> s = MEMORY_SESSIONS.remove(sessionId);
            if (s != null) {
                s.setStatus("CANCELED");
            }
            return;
        }
        importSessionRepo.findByRefName(sessionId).ifPresent(s -> {
            s.setStatus("CANCELLED");
            importSessionRepo.save(s);
        });
        // delete per-row data for this session to free storage
        int skip = 0;
        int pageSize = BATCH_SIZE;
        while (true) {
            java.util.List<dev.morphia.query.filters.Filter> filters = new java.util.ArrayList<>();
            filters.add(dev.morphia.query.filters.Filters.eq("sessionRefName", sessionId));
            java.util.List<com.e2eq.framework.model.persistent.imports.ImportSessionRow> page = importSessionRowRepo.getList(skip, pageSize, filters, null);
            if (page == null || page.isEmpty()) break;
            for (com.e2eq.framework.model.persistent.imports.ImportSessionRow r : page) {
                try {
                    importSessionRowRepo.delete(r);
                } catch (Exception ignore) {
                }
            }
            // do not increase skip since we delete as we go; always fetch from 0 until empty
            skip = 0;
        }
    }

    // ==================== ImportProfile-enabled methods ====================

    /**
     * Analyze CSV with ImportProfile support.
     * This method applies value mappings, lookups, and transformations defined in the profile.
     *
     * @param repo the repository for the target entity
     * @param inputStream the CSV input stream
     * @param profile the import profile (may be null for backward compatibility)
     * @param requestedColumns the columns to import (used when profile is null)
     * @param realmId the realm ID for lookups
     * @return the analysis result with session ID for preview/commit workflow
     */
    public <T extends UnversionedBaseModel> ImportResult<T> analyzeCSVWithProfile(
            BaseMorphiaRepo<T> repo,
            InputStream inputStream,
            ImportProfile profile,
            List<String> requestedColumns,
            String realmId) throws IOException {

        // Use profile settings or defaults
        char fieldSeparator = profile != null ? profile.getDelimiterChar() : ',';
        char quoteChar = profile != null ? profile.getQuoteCharChar() : '"';
        boolean skipHeaderRow = profile == null || profile.hasHeaderRow();
        Charset charset = Charset.forName(profile != null ? profile.getEncodingOrDefault() : "UTF-8");

        ImportResult<T> result = new ImportResult<>(0, 0);
        boolean memMode = (importSessionRepo == null || importSessionRowRepo == null);
        List<ImportRowResult<T>> memRows = memMode ? new ArrayList<>() : null;

        String sessionId = java.util.UUID.randomUUID().toString();
        result.setSessionId(sessionId);

        com.e2eq.framework.model.persistent.imports.ImportSession session =
                memMode ? null : openImportSession(sessionId, repo, profile);
        DuplicateReferenceRows references = new DuplicateReferenceRows();
        List<String> importedColumns = requestedColumns;

        try (Reader reader = makeReader(inputStream, charset, false)) {
            ICsvDozerBeanReader beanReader = new CsvDozerBeanReader(reader,
                    new CsvPreference.Builder(quoteChar, fieldSeparator, "\r\n")
                            .useQuoteMode(new NormalQuoteMode())
                            .build());

            String[] rawHeaders = beanReader.getHeader(skipHeaderRow);
            if (skipHeaderRow && rawHeaders == null) {
                throw new IllegalArgumentException("CSV file does not contain a header row");
            }

            // Parse headers with modifiers if enabled
            List<ParsedHeader> parsedHeaders = importProfileService.parseHeaders(profile, rawHeaders);

            // Build field mapping from parsed headers
            List<String> effectiveColumns = requestedColumns;
            if (effectiveColumns == null || effectiveColumns.isEmpty()) {
                effectiveColumns = new ArrayList<>();
                for (ParsedHeader ph : parsedHeaders) {
                    effectiveColumns.add(ph.getFieldName());
                }
            }

            importedColumns = effectiveColumns;
            final String[] fieldMapping = effectiveColumns.toArray(new String[0]);
            beanReader.configureBeanMapping(repo.getPersistentClass(), fieldMapping);

            // Build processors with profile transformations
            CellProcessor[] processors = buildProcessorsWithProfile(
                    repo.getPersistentClass(), effectiveColumns, profile, realmId);

            // Initialize dynamic attribute processing context if enabled
            DynamicAttributeImportService.RowContext dynamicAttrContext = null;
            if (profile != null && dynamicAttributeImportService != null &&
                    dynamicAttributeImportService.isEnabled(profile)) {
                dynamicAttrContext = dynamicAttributeImportService.initializeContext(rawHeaders, profile);
            }

            List<T> batchBeans = new ArrayList<>(BATCH_SIZE);
            List<Integer> batchRowNums = new ArrayList<>(BATCH_SIZE);
            Map<Integer, String> batchRawByRowNum = new HashMap<>(BATCH_SIZE);
            Map<Integer, Map<String, Object>> batchRowDataByRowNum = new HashMap<>(BATCH_SIZE);

            int rowNum = 1;
            while (true) {
                T bean;
                Map<String, Object> rowData = new HashMap<>();

                try {
                    bean = beanReader.read(repo.getPersistentClass(), processors);

                    // Capture row data for field calculators
                    if (bean != null) {
                        String[] currentRow = beanReader.getUntokenizedRow() != null ?
                            beanReader.getUntokenizedRow().split(String.valueOf(fieldSeparator)) : new String[0];
                        for (int i = 0; i < Math.min(fieldMapping.length, currentRow.length); i++) {
                            rowData.put(fieldMapping[i], currentRow[i]);
                        }
                    }
                } catch (SuperCsvException ex) {
                    String field = resolveFieldFromParseException(ex, fieldMapping);
                    ImportRowResult<T> rr = new ImportRowResult<>();
                    rr.setRowNumber(rowNum);
                    rr.setIntent(Intent.SKIP);
                    rr.setRawData(safeRaw(beanReader));
                    rr.getErrors().add(new FieldError(field, ex.getMessage(), FieldErrorCode.PARSE));

                    persistOrStoreRow(memMode, sessionId, rr, memRows, result);
                    rowNum++;
                    continue;
                } catch (Exception ex) {
                    ImportRowResult<T> rr = new ImportRowResult<>();
                    rr.setRowNumber(rowNum);
                    rr.setIntent(Intent.SKIP);
                    rr.setRawData(safeRaw(beanReader));
                    rr.getErrors().add(new FieldError(null, ex.getMessage(), FieldErrorCode.PARSE));

                    persistOrStoreRow(memMode, sessionId, rr, memRows, result);
                    rowNum++;
                    continue;
                }

                if (bean == null) break; // EOF

                batchRawByRowNum.put(rowNum, safeRaw(beanReader));
                batchRowDataByRowNum.put(rowNum, rowData);
                batchBeans.add(bean);
                batchRowNums.add(rowNum);

                if (batchBeans.size() >= BATCH_SIZE) {
                    List<ImportRowResult<T>> annotated = processProfileBatch(repo, batchBeans, batchRowNums,
                            batchRawByRowNum, batchRowDataByRowNum, result, profile, realmId, sessionId,
                            effectiveColumns, rawHeaders, dynamicAttrContext, fieldSeparator);
                    stageAnnotatedRows(memMode, sessionId, annotated, memRows, result, references);

                    batchBeans.clear();
                    batchRowNums.clear();
                    batchRawByRowNum.clear();
                    batchRowDataByRowNum.clear();
                }

                rowNum++;
            }

            // Process remaining batch
            if (!batchBeans.isEmpty()) {
                List<ImportRowResult<T>> annotated = processProfileBatch(repo, batchBeans, batchRowNums,
                        batchRawByRowNum, batchRowDataByRowNum, result, profile, realmId, sessionId,
                        effectiveColumns, rawHeaders, dynamicAttrContext, fieldSeparator);
                stageAnnotatedRows(memMode, sessionId, annotated, memRows, result, references);
            }
        } catch (IOException e) {
            throw new WebApplicationException("Error analyzing CSV: " + e.getMessage(), e, 400);
        }

        applyDuplicateReferenceErrors(references, memMode, sessionId, memRows, result);
        closeImportSession(memMode, session, sessionId, repo, memRows, result, importedColumns);
        return result;
    }

    /** Run profile hooks on merged copies, so partial UPDATE rows have their existing values.
     * Track only fields supplied by the file or changed by hooks, independently for each row. */
    private <T extends UnversionedBaseModel> List<ImportRowResult<T>> processProfileBatch(
            BaseMorphiaRepo<T> repo, List<T> beans, List<Integer> rowNumbers,
            Map<Integer, String> raw, Map<Integer, Map<String, Object>> rowData,
            ImportResult<T> result, ImportProfile profile, String realmId, String sessionId,
            List<String> columns, String[] rawHeaders,
            DynamicAttributeImportService.RowContext dynamicAttrContext, char fieldSeparator) {
        Map<String, T> existing = findExistingByRefName(repo, beans);
        List<T> prepared = new ArrayList<>();
        List<Integer> preparedNumbers = new ArrayList<>();
        Map<T, JsonNode> beforeHooks = new IdentityHashMap<>();
        Map<T, T> mergedOnto = new IdentityHashMap<>();
        List<ImportRowResult<T>> rows = new ArrayList<>();
        for (int i = 0; i < beans.size(); i++) {
            T bean = beans.get(i);
            int number = rowNumbers.get(i);
            ImportRowResult<T> rr = new ImportRowResult<>();
            rr.setRowNumber(number);
            rr.setRefName(bean.getRefName());
            rr.setRawData(raw.get(number));
            try {
                T current = bean.getRefName() == null ? null : existing.get(bean.getRefName());
                String requestedRef = bean.getRefName();
                if (current != null) {
                    bean = mergedCopy(current, bean, columns);
                    // Row results retain the reference as supplied by the file.
                    bean.setRefName(requestedRef);
                    mergedOnto.put(bean, current);
                }
                beforeHooks.put(bean, mapper().valueToTree(bean));
                rr.setRecord(bean);
                if (!applyProfileToRow(repo, bean, rowData.get(number), profile, realmId,
                        sessionId, rr, rawHeaders, dynamicAttrContext, fieldSeparator)) {
                    rows.add(rr);
                    continue;
                }
                prepared.add(bean);
                preparedNumbers.add(number);
            } catch (Exception e) {
                rr.getErrors().add(new FieldError(null, e.getMessage(), FieldErrorCode.VALIDATION));
                rows.add(rr);
            }
        }
        List<ImportRowResult<T>> annotated = new ArrayList<>();
        if (!applyBatchHandlersAndValidate(repo, prepared, preparedNumbers, raw, rowData,
                annotated, result, profile, realmId, sessionId)) {
            Map<T, List<String>> masks = new IdentityHashMap<>();
            for (T bean : prepared) {
                java.util.Set<String> mask = new java.util.LinkedHashSet<>(columns);
                changedPaths(beforeHooks.get(bean), mapper().valueToTree(bean), "", mask);
                masks.put(bean, List.copyOf(mask));
            }
            annotateIntentsAndValidateWithProfile(repo, prepared, preparedNumbers, raw, rowData,
                    annotated, result, profile, masks, mergedOnto);
        }
        rows.addAll(annotated);
        return rows;
    }

    /** Arrays and collections are replacement values; object changes keep their leaf paths. */
    private static void changedPaths(JsonNode before,
            JsonNode after, String prefix, java.util.Set<String> paths) {
        if (java.util.Objects.equals(before, after)) return;
        if (before != null && after != null && before.isObject() && after.isObject()) {
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            before.fieldNames().forEachRemaining(names::add);
            after.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                changedPaths(before.get(name), after.get(name), prefix.isEmpty() ? name : prefix + "." + name, paths);
            }
        } else if (!prefix.isEmpty()) {
            paths.add(prefix);
        }
    }

    private <T extends UnversionedBaseModel> boolean applyProfileToRow(
            BaseMorphiaRepo<T> repo, T bean, Map<String, Object> rowData, ImportProfile profile,
            String realmId, String sessionId, ImportRowResult<T> rr, String[] rawHeaders,
            DynamicAttributeImportService.RowContext dynamicAttrContext, char fieldSeparator) {
        int rowNum = rr.getRowNumber();
        // Apply field calculators and row value resolvers if profile is set
        if (profile != null && bean instanceof BaseModel baseModel) {
            ImportContext context = ImportContext.builder()
                    .profile(profile)
                    .targetClass(repo.getPersistentClass())
                    .realmId(realmId)
                    .rowNumber(rowNum)
                    .sessionId(sessionId)
                    .build();

            // Apply field calculators
            importProfileService.applyFieldCalculators(profile, baseModel, rowData, context);

            // Apply row value resolvers (arbitrary code per-column with full row access)
            com.e2eq.framework.imports.spi.RowValueResolver.ResolveResult resolverResult =
                    importProfileService.applyRowValueResolvers(profile, baseModel, rowData, context);

            if (!resolverResult.isSuccess()) {
                rr.setIntent(Intent.SKIP);
                rr.getErrors().add(new FieldError(null, resolverResult.getErrorMessage(), FieldErrorCode.VALIDATION));
                return false;
            }

            if (resolverResult.isSkip()) {
                rr.setIntent(Intent.SKIP);
                return false;
            }

            // Process dynamic attributes if enabled and model supports it
            if (dynamicAttrContext != null && baseModel instanceof DynamicAttributeSupport dynamicModel) {
                // Create full row data map including raw headers for dynamic attribute extraction
                Map<String, Object> fullRowData = new HashMap<>(rowData);
                // Also populate with raw header values for dynamic attribute columns
                String untokenized = rr.getRawData();
                if (untokenized != null) {
                    String[] rawValues = untokenized.split(String.valueOf(fieldSeparator), -1);
                    for (int idx = 0; idx < Math.min(rawHeaders.length, rawValues.length); idx++) {
                        fullRowData.put(rawHeaders[idx], rawValues[idx]);
                    }
                }

                List<DynamicAttributeSet> existingSets = dynamicModel.getDynamicAttributeSets();
                List<DynamicAttributeSet> mergedSets = dynamicAttributeImportService.processRow(
                        fullRowData, dynamicAttrContext, existingSets);
                dynamicModel.setDynamicAttributeSets(mergedSets);
            }

            // Apply pre-validation transformers
            PreValidationTransformer.TransformResult transformResult =
                    importProfileService.applyPreValidationTransformers(profile, baseModel, rowData, context);

            if (!transformResult.isSuccess()) {
                rr.setIntent(Intent.SKIP);
                rr.getErrors().add(new FieldError(null, transformResult.getErrorMessage(), FieldErrorCode.VALIDATION));
                return false;
            }

            if (transformResult.isSkip()) {
                rr.setIntent(Intent.SKIP);
                return false;
            }
        }

        return true;
    }

    /**
     * Build cell processors with ImportProfile transformations.
     */
    private CellProcessor[] buildProcessorsWithProfile(Class<?> clazz, List<String> cols,
                                                        ImportProfile profile, String realmId) {
        CellProcessor[] processors = new CellProcessor[cols.size()];
        ListCellProcessor listProcessor = new ListCellProcessor();

        for (int i = 0; i < cols.size(); i++) {
            String fieldName = cols.get(i);

            // Build base processor for the field type
            CellProcessor baseProcessor;
            if (fieldName.contains("[")) {
                baseProcessor = new org.supercsv.cellprocessor.Optional(listProcessor);
            } else {
                Class<?> type = getFieldType(clazz, fieldName);
                if (type == int.class || type == Integer.class) {
                    baseProcessor = new org.supercsv.cellprocessor.Optional(new ParseInt());
                } else if (type == long.class || type == Long.class) {
                    baseProcessor = new org.supercsv.cellprocessor.Optional(new ParseLong());
                } else if (type == double.class || type == Double.class ||
                        type == float.class || type == Float.class) {
                    baseProcessor = new org.supercsv.cellprocessor.Optional(new ParseDouble());
                } else if (type == java.math.BigDecimal.class) {
                    baseProcessor = new org.supercsv.cellprocessor.Optional(new ParseBigDecimal());
                } else if (type != null && type.isEnum()) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Enum<?>> e = (Class<? extends Enum<?>>) type;
                    baseProcessor = new Optional(new ParseEnum(e));
                } else {
                    baseProcessor = new org.supercsv.cellprocessor.Optional();
                }
            }

            // Wrap with profile transformations if available
            if (profile != null && importProfileService != null) {
                processors[i] = importProfileService.buildProcessorForColumn(profile, fieldName, realmId, baseProcessor);
            } else {
                processors[i] = baseProcessor;
            }
        }

        return processors;
    }

    /**
     * Helper to persist or store error rows.
     */
    private <T extends UnversionedBaseModel> void persistOrStoreRow(
            boolean memMode, String sessionId, ImportRowResult<T> rr,
            List<ImportRowResult<T>> memRows, ImportResult<T> result) {

        result.incrementTotalRows();
        result.incrementErrorRows();

        if (result.getRowResults().size() < PREVIEW_LIMIT) {
            result.getRowResults().add(rr);
        }

        if (!memMode) {
            importSessionRowRepo.save(toStagingRow(sessionId, rr));
        } else {
            memRows.add(rr);
        }
    }

    /**
     * Helper to persist or store annotated rows.
     */
    private <T extends UnversionedBaseModel> void persistOrStoreAnnotatedRow(
            boolean memMode, String sessionId, ImportRowResult<T> ar,
            List<ImportRowResult<T>> memRows, ImportResult<T> result) {

        result.incrementTotalRows();
        boolean hasErrors = ar.hasErrors();

        if (hasErrors) {
            result.incrementErrorRows();
        } else {
            result.incrementValidRows();
            if (ar.getIntent() == Intent.INSERT) {
                result.setInsertCount(result.getInsertCount() + 1);
            } else if (ar.getIntent() == Intent.UPDATE) {
                result.setUpdateCount(result.getUpdateCount() + 1);
            }
        }

        if (result.getRowResults().size() < PREVIEW_LIMIT) {
            result.getRowResults().add(ar);
        }

        if (!memMode) {
            importSessionRowRepo.save(toStagingRow(sessionId, ar));
        } else {
            memRows.add(ar);
        }
    }

    /**
     * Builds the staging row persisted for an analyzed row. The row's {@code refName} is its
     * staging identity ({@code <sessionId>:<rowNumber>}); the reference of the document it would
     * insert or update is kept in {@code targetRefName}.
     *
     * @param sessionId the import session's refName
     * @param rowResult the analyzed row
     * @param <T> target record type
     * @return a new, unsaved staging row
     */
    public <T> com.e2eq.framework.model.persistent.imports.ImportSessionRow toStagingRow(
            String sessionId, ImportRowResult<T> rowResult) {
        com.e2eq.framework.model.persistent.imports.ImportSessionRow row =
                new com.e2eq.framework.model.persistent.imports.ImportSessionRow();
        row.setSessionRefName(sessionId);
        row.setRowNumber(rowResult.getRowNumber());
        row.setRefName(com.e2eq.framework.model.persistent.imports.ImportSessionRow.stagingRefName(sessionId, rowResult.getRowNumber()));
        row.setTargetRefName(rowResult.getRefName());
        row.setIntent(rowResult.getIntent().name());
        row.setImportedColumns(rowResult.getImportedColumns());
        row.setHasErrors(rowResult.hasErrors());
        row.setRawLine(rowResult.getRawData());
        try {
            row.setErrorsJson(objectMapper.writeValueAsString(rowResult.getErrors()));
            if (rowResult.getRecord() != null) {
                row.setRecordJson(objectMapper.writeValueAsString(rowResult.getRecord()));
            }
        } catch (Exception ignore) {
        }
        return row;
    }

    /**
     * Apply batch lifecycle handlers (beforeBatch). If any handler fails,
     * populate annotated with error rows and return true. Otherwise return false.
     *
     * @return true if batch handler failed (annotated has errors), false to proceed with validation
     */
    private <T extends UnversionedBaseModel> boolean applyBatchHandlersAndValidate(
            BaseMorphiaRepo<T> repo,
            List<T> batchBeans,
            List<Integer> batchRowNums,
            Map<Integer, String> rawByRowNum,
            Map<Integer, Map<String, Object>> rowDataByRowNum,
            List<ImportRowResult<T>> annotated,
            ImportResult<T> result,
            ImportProfile profile,
            String realmId,
            String sessionId) {

        if (profile == null || profile.getBatchLifecycleHandlerNames() == null
                || profile.getBatchLifecycleHandlerNames().isEmpty()) {
            return false;
        }

        ImportBatch<T> batch = new ImportBatch<>(batchBeans, batchRowNums, rawByRowNum, rowDataByRowNum);
        int firstRowNum = batchRowNums.isEmpty() ? 1 : batchRowNums.get(0);
        ImportContext context = ImportContext.builder()
                .profile(profile)
                .targetClass(repo.getPersistentClass())
                .realmId(realmId)
                .rowNumber(firstRowNum)
                .sessionId(sessionId)
                .build();

        BatchLifecycleHandler.BatchResult batchResult =
                importProfileService.applyBatchLifecycleHandlers(profile, batch, context);

        if (batchResult.isSuccess()) {
            return false;
        }

        // Handler failed - add error row for each bean
        String errorMsg = batchResult.getErrorMessage();
        for (int i = 0; i < batchBeans.size(); i++) {
            T bean = batchBeans.get(i);
            int rn = i < batchRowNums.size() ? batchRowNums.get(i) : (firstRowNum + i);
            ImportRowResult<T> rr = new ImportRowResult<>();
            rr.setRowNumber(rn);
            rr.setRefName(bean.getRefName());
            rr.setRecord(bean);
            rr.setIntent(Intent.SKIP);
            rr.setRawData(rawByRowNum != null ? rawByRowNum.get(rn) : null);
            rr.getErrors().add(new FieldError(null, errorMsg != null ? errorMsg : "Batch handler failed",
                    FieldErrorCode.VALIDATION));
            annotated.add(rr);
        }
        return true;
    }

    /**
     * Annotate intents and validate with ImportProfile support.
     */
    private <T extends UnversionedBaseModel> void annotateIntentsAndValidateWithProfile(
            BaseMorphiaRepo<T> repo,
            List<T> batchBeans,
            List<Integer> batchRowNums,
            Map<Integer, String> rawByRowNum,
            Map<Integer, Map<String, Object>> rowDataByRowNum,
            List<ImportRowResult<T>> outRows,
            ImportResult<T> aggregate,
            ImportProfile profile, Map<T, List<String>> rowColumns, Map<T, T> mergedOnto) {

        Map<String, T> existing = findExistingByRefName(repo, batchBeans);

        for (int i = 0; i < batchBeans.size(); i++) {
            T bean = batchBeans.get(i);
            int rn = batchRowNums.get(i);
            Map<String, Object> rowData = rowDataByRowNum.get(rn);
            boolean exists = exists(existing, bean);

            ImportRowResult<T> rr = new ImportRowResult<>();
            rr.setRowNumber(rn);
            rr.setRefName(bean.getRefName());
            rr.setRecord(bean);
            rr.setImportedColumns(rowColumns.get(bean));

            // Determine intent from profile or default
            Intent intent;
            if (profile != null && profile.getIntentColumn() != null && rowData != null) {
                ImportIntent profileIntent = importProfileService.parseIntent(profile, rowData);
                intent = mapProfileIntent(profileIntent, exists);
            } else {
                intent = exists ? Intent.UPDATE : Intent.INSERT;
            }
            rr.setIntent(intent);
            if (intent == Intent.INSERT && exists) {
                rr.getErrors().add(new FieldError("refName",
                        format("Reference '%s' already exists, so this row cannot be inserted.", bean.getRefName()),
                        FieldErrorCode.VALIDATION));
            } else if (intent == Intent.UPDATE && !exists) {
                rr.getErrors().add(new FieldError("refName",
                        format("Reference '%s' does not exist, so this row cannot be updated.", bean.getRefName()),
                        FieldErrorCode.VALIDATION));
            }
            // Hooks ran on a copy of the record the file's reference matched, so the row carries that
            // record's identity and values. If they changed the reference, the row no longer
            // describes that record, and committing it would overwrite or rename it.
            T target = bean.getRefName() == null ? null : existing.get(bean.getRefName());
            ObjectId targetId = target == null ? null : target.getId();
            if (bean.getId() != null && !bean.getId().equals(targetId)) {
                T original = mergedOnto.get(bean);
                rr.getErrors().add(new FieldError("refName", original != null
                        ? format("The import profile changed the reference of existing record '%s' to '%s'; "
                                + "a row that matches an existing record cannot change its reference.",
                                original.getRefName(), bean.getRefName())
                        : format("Reference '%s' does not match the id the row carries.", bean.getRefName()),
                        FieldErrorCode.VALIDATION));
            }

            // Attach raw data
            String raw = rawByRowNum != null ? rawByRowNum.get(rn) : null;
            if (raw != null) {
                rr.setRawData(raw);
            }

            // Validate
            try {
                T current = bean.getRefName() == null ? null : existing.get(bean.getRefName());
                Set<ConstraintViolation<T>> violations = validator.validate(current == null ? bean
                        : mergedCopy(current, bean, rowColumns.get(bean)));
                if (violations != null) {
                    for (ConstraintViolation<T> v : violations) {
                        rr.getErrors().add(new FieldError(
                                v.getPropertyPath() != null ? v.getPropertyPath().toString() : null,
                                v.getMessage(),
                                FieldErrorCode.VALIDATION));
                    }
                }
            } catch (Exception ex) {
                rr.getErrors().add(new FieldError(null, ex.getMessage(), FieldErrorCode.VALIDATION));
            }

            outRows.add(rr);
        }
    }

    /**
     * Map ImportIntent from profile to internal Intent enum.
     */
    private Intent mapProfileIntent(ImportIntent profileIntent, boolean exists) {
        if (profileIntent == null) {
            return exists ? Intent.UPDATE : Intent.INSERT;
        }

        switch (profileIntent) {
            case INSERT:
                return Intent.INSERT;
            case UPDATE:
                return Intent.UPDATE;
            case SKIP:
                return Intent.SKIP;
            case UPSERT:
            default:
                return exists ? Intent.UPDATE : Intent.INSERT;
        }
    }
}

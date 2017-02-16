package diamond.cms.server.dao;

import static org.jooq.impl.DSL.using;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jooq.Condition;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.RecordMapper;
import org.jooq.Schema;
import org.jooq.SelectLimitStep;
import org.jooq.SelectSeekStepN;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.UniqueKey;
import org.jooq.UpdatableRecord;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import diamond.cms.server.core.PageResult;

public class JOOQGenericDao<T, ID extends Serializable> implements GenericDao<T, ID> {

    private Table<? extends Record> table = null;
    private Class<T> entityClass = null;
    private Field<ID> primaryKey = null;
    Logger log = LoggerFactory.getLogger(this.getClass());
    private final DSLContext dslContext;

    public DSLContext getDSLContext() {
        return dslContext;
    }

    public JOOQGenericDao(Class<T> entityClass, Schema schema, Configuration configuration) {
        this.entityClass = entityClass;
        initTable(schema);
        primaryKey = pk();
        dslContext = using(configuration);
    }

    @Override
    public T insert(T entity) {
        record(entity, false, getDSLContext()).store();
        return entity;
    }

    @Override
    public void insert(Collection<T> entities) {
        if (entities.isEmpty()) {
            return;
        }

        List<UpdatableRecord<?>> rs = records(entities, false, true);

        if (rs.size() > 1) {
            getDSLContext().batchInsert(rs).execute();
            return;
        }

        rs.get(0).insert();
    }

    @Override
    public T update(T entity) {
        record(entity, true, getDSLContext()).update();
        return entity;
    }

    @Override
    public void update(Collection<T> entities) {
        if (entities.isEmpty()) {
            return;
        }

        List<UpdatableRecord<?>> rs = records(entities, true, true);

        if (rs.size() > 1) {
            getDSLContext().batchUpdate(rs).execute();
            return;
        }

        rs.get(0).update();
    }

    @Override
    public int deleteById(ID id) {
        return getDSLContext().delete(table).where(primaryKey.equal(id)).execute();
    }

    @Override
    public void deleteByIds(Collection<ID> ids) {
        getDSLContext().delete(table).where(primaryKey.in(ids)).execute();
    }

    @Override
    public void delete(Condition... conditions) {
        delete(Stream.of(conditions));
    }

    @Override
    public void deleteWithOptional(Stream<Optional<Condition>> conditions) {
        delete(conditions.filter(Optional::isPresent).map(Optional::get));
    }

    @Override
    public void delete(Stream<Condition> conditions) {
        Optional<Condition> o = conditions.reduce((acc, item) -> acc.and(item));
        Condition c = o.orElseThrow(
                () -> new IllegalArgumentException("At least one condition is needed to perform deletion"));
        getDSLContext().delete(table).where(c).execute();
    }

    @Override
    public T get(ID id) {
        return getOptional(id).orElse(null);
    }

    @Override
    public Optional<T> getOptional(ID id) {
        Record record = getDSLContext().select().from(table).where(primaryKey.eq(id)).fetchOne();
        return Optional.ofNullable(record).map(r -> r.into(entityClass));
    }

    @Override
    public List<T> get(Collection<ID> ids) {
        return getDSLContext().select().from(table).where(primaryKey.in(ids)).fetch().into(entityClass);
    }

    @Override
    public int count(Condition... conditions) {
        return count(Stream.of(conditions));
    }

    @Override
    public int countWithOptional(Stream<Optional<Condition>> conditions) {
        return count(conditions.filter(Optional::isPresent).map(Optional::get));
    }

    @Override
    public int count(Stream<Condition> conditions) {
        Condition c = conditions.reduce((acc, item) -> acc.and(item)).orElse(DSL.trueCondition());
        return getDSLContext().fetchCount(table, c);
    }

    @Override
    public List<T> fetch(Condition... conditions) {
        return fetch(Stream.of(conditions));
    }

    @Override
    public List<T> fetchWithOptional(Stream<Optional<Condition>> conditions, SortField<?>... sorts) {
        return fetch(conditions.filter(Optional::isPresent).map(Optional::get), sorts);
    }

    @Override
    public List<T> fetch(Stream<Condition> conditions, SortField<?>... sorts) {
        Condition c = conditions.reduce((acc, item) -> acc.and(item)).orElse(DSL.trueCondition());
        SelectSeekStepN<Record> step = getDSLContext().select().from(table).where(c).orderBy(sorts);
        return step.fetchInto(entityClass);
    }

    @Override
    public PageResult<T> fetch(PageResult<T> page, Condition... conditions) {
        return fetch(page, Stream.of(conditions));
    }

    @Override
    public PageResult<T> fetch(PageResult<T> page, SortField<?> sort) {
        return fetch(page, Stream.empty(), sort);
    }

    @Override
    public PageResult<T> fetchWithOptional(PageResult<T> page, Stream<Optional<Condition>> conditions,
            SortField<?>... sorts) {
        return fetch(page, conditions.filter(Optional::isPresent).map(Optional::get), sorts);
    }

    @Override
    public PageResult<T> fetch(PageResult<T> page, Stream<Condition> conditions, SortField<?>... sorts) {
        Condition c = conditions.reduce((acc, item) -> acc.and(item)).orElse(DSL.trueCondition());
        return fetch(page, e -> {
            return e.select(table.fields()).from(table).where(c).orderBy(sorts);
        }, entityClass);
    }

    @Override
    public Optional<T> fetchOne(Condition... conditions) {
        return fetchOne(Stream.of(conditions));
    }

    @Override
    public Optional<T> fetchOneWithOptional(Stream<Optional<Condition>> conditions) {
        return fetchOne(conditions.filter(Optional::isPresent).map(Optional::get));
    }

    @Override
    public Optional<T> fetchOne(Stream<Condition> conditions) {
        List<T> list = fetch(conditions);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public <O> O execute(Executor<O> cb) {
        return cb.execute(getDSLContext());
    }

    @Override
    public PageResult<T> fetch(PageResult<T> page, Executor<SelectLimitStep<?>> ec, RecordMapper<Record, T> mapper) {
        DSLContext context = getDSLContext();
        SelectLimitStep<?> r = ec.execute(context);
        int count = context.fetchCount(r);
        page.setTotal(count);
        List<T> list = r.limit(page.getStart(), page.getPageSize()).fetch(mapper);
        page.setData(list);
        return page;
    }

    private void initTable(Schema schema) {
        Class<?> is = findInterface(entityClass)
                .orElseThrow(() -> new RuntimeException("Entity class must implements one interface at least."));
        table = schema.getTables().stream().filter(t -> is.isAssignableFrom(t.getRecordType())).findFirst()
                .orElseThrow(() -> new RuntimeException("Can't find a table for the entity."));
    }

    @SuppressWarnings("unchecked")
    private Field<ID> pk() {
        UniqueKey<?> uk = table.getPrimaryKey();
        Field<?>[] fs = uk.getFieldsArray();
        return (Field<ID>) fs[0];
    }

    private List<UpdatableRecord<?>> records(Collection<T> objects, boolean forUpdate, boolean ignoreNull) {
        DSLContext context = getDSLContext();
        return objects.stream().map(obj -> record(obj, forUpdate, context, ignoreNull)).collect(Collectors.toList());
    }

    private UpdatableRecord<?> record(T object, boolean forUpdate, DSLContext context) {
        return record(object, forUpdate, context, true);
    }

    private UpdatableRecord<?> record(T object, boolean forUpdate, DSLContext context, boolean ignoreNull) {
        UpdatableRecord<?> r = (UpdatableRecord<?>) context.newRecord(table, object);
        if (forUpdate) {
            r.changed(primaryKey, false);
        }

        int size = r.size();

        if (ignoreNull) {
            for (int i = 0; i < size; i++) {
                if (r.getValue(i) == null) {
                    r.changed(i, false);
                }
            }
        }
        return r;
    }

    private Optional<Class<?>> findInterface(Class<?> clazz) {
        if (Object.class == clazz) {
            return Optional.empty();
        }
        Class<?>[] is = clazz.getInterfaces();
        for (Class<?> c : is) {
            if (c.getSimpleName().startsWith("I")) {
                return Optional.of(c);
            }
        }
        return findInterface(clazz.getSuperclass());
    }

    public PageResult<T> fetch(PageResult<T> page, Executor<SelectLimitStep<?>> ec, Class<T> clazz) {
        this.fetch(page, ec, r -> {
            return mapperEntityEx(r, clazz);
        });
        return page;
    }

    public  <E> E mapperEntityEx(Record r, Class<E> clazz) {
        try {
            E entity = clazz.newInstance();
            Map<String,Method> entityMethodMap = getSetMethods(clazz);
            Arrays.asList(r.fields()).forEach(f -> {
                String name = f.getName();
                Object value = r.getValue(name);
                if (value != null) //opt: when the field's value is null , don't do the set operator,
                    setObjectValue(name, value, entity, entityMethodMap);
            });
            return entity;
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    private <E> Map<String, Method> getSetMethods(Class<E> clazz) {
        Map<String,Method> entityMethodMap = new HashMap<>();
        Arrays.asList(clazz.getMethods()).forEach(m -> {
            if (m.getName().startsWith("set")){
                entityMethodMap.put(m.getName(), m);
            }
        });
        return entityMethodMap;
    }

    private <E> void setObjectValue(String name, Object value, E entity, Map<String, Method> entityMethodMap) {
        StringBuffer setMethodName = new StringBuffer();
        setMethodName.append("set");
        if (name.indexOf("_") != -1) {
            Arrays.asList(name.split("_")).forEach(n -> {
                setMethodName.append(toCamelCase(n));
            });
        } else {
            setMethodName.append(toCamelCase(name));
        }
        try {
            Method m = entityMethodMap.get(setMethodName.toString());
            if (m != null) {
                m.invoke(entity, value);
            } else if (log.isDebugEnabled()) {
                log.debug(setMethodName + " for entity " + entity.getClass().getName() + " not exists");
            }
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            if (log.isWarnEnabled()) {
                log.warn(
                        "class: " + entity.getClass().getName() +
                        " invok method " + setMethodName + " with " + value +" failed:" + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private String toCamelCase(String name) {
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    @Override
    public T update(T entiry, boolean ignoreNull) {
        record(entiry, true, getDSLContext(),ignoreNull).update();
        return entiry;
    }

    @Override
    public void update(Collection<T> entities, boolean ignoreNull) {
        if (entities.isEmpty()) {
            return;
        }

        List<UpdatableRecord<?>> rs = records(entities, true, ignoreNull);

        if (rs.size() > 1) {
            getDSLContext().batchUpdate(rs).execute();
            return;
        }

        rs.get(0).update();
    }

}

package org.odata4j.producer.inmemory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import org.core4j.Enumerable;
import org.core4j.Func;
import org.core4j.Func1;
import org.core4j.Predicate1;
import org.odata4j.core.*;
import org.odata4j.edm.*;
import org.odata4j.edm.EdmProperty.CollectionKind;
import org.odata4j.expression.BoolCommonExpression;
import org.odata4j.expression.EntitySimpleProperty;
import org.odata4j.expression.Expression;
import org.odata4j.expression.OrderByExpression;
import org.odata4j.expression.OrderByExpression.Direction;
import org.odata4j.producer.*;
import org.odata4j.producer.edm.MetadataProducer;
import org.odata4j.producer.exceptions.NotFoundException;
import org.odata4j.producer.exceptions.NotImplementedException;

/**
 * An in-memory implementation of an ODATA Producer.  Uses the standard Java bean
 * and property model to access information within entities.
 */
public class InMemoryProducer implements ODataProducer {

  public static final String ID_PROPNAME = "EntityId";

  private final String namespace;
  private final String containerName;
  private final int maxResults;
  // preserve the order of registration
  private final Map<String, InMemoryEntityInfo<?>> eis = new LinkedHashMap<String, InMemoryEntityInfo<?>>();
  private final Map<String, InMemoryComplexTypeInfo<?>> complexTypes = new LinkedHashMap<String, InMemoryComplexTypeInfo<?>>();
  private EdmDataServices metadata;
  private final EdmDecorator decorator;
  private final MetadataProducer metadataProducer;
  private final InMemoryTypeMapping typeMapping;
  
  private boolean includeNullPropertyValues = true;
  private final boolean flattenEdm;
  
  private static final int DEFAULT_MAX_RESULTS = 100;

  /**
   * Creates a new instance of an in-memory POJO producer.
   *
   * @param namespace  the namespace of the schema registrations
   */
  public InMemoryProducer(String namespace) {
    this(namespace, DEFAULT_MAX_RESULTS);
  }

  /**
   * Creates a new instance of an in-memory POJO producer.
   *
   * @param namespace  the namespace of the schema registrations
   * @param maxResults  the maximum number of entities to return in a single call
   */
  public InMemoryProducer(String namespace, int maxResults) {
    this(namespace, null, maxResults, null, null);
  }

  /**
   * Creates a new instance of an in-memory POJO producer.
   *
   * @param namespace  the namespace of the schema registrations
   * @param containerName  the container name for generated metadata
   * @param maxResults  the maximum number of entities to return in a single call
   * @param decorator  a decorator to use for edm customizations
   * @param typeMapping  optional mapping between java types and edm types, null for default
   */
  public InMemoryProducer(String namespace, String containerName, int maxResults, EdmDecorator decorator, InMemoryTypeMapping typeMapping) {
    this(namespace, containerName, maxResults, decorator, typeMapping,
            true); // legacy: flatten edm
  }
  
  public InMemoryProducer(String namespace, String containerName, int maxResults, EdmDecorator decorator, InMemoryTypeMapping typeMapping,
          boolean flattenEdm) {
    this.namespace = namespace;
    this.containerName = containerName != null && !containerName.isEmpty() ? containerName : "Container";
    this.maxResults = maxResults;
    this.decorator = decorator;
    this.metadataProducer = new MetadataProducer(this, decorator);
    this.typeMapping = typeMapping == null ? InMemoryTypeMapping.DEFAULT : typeMapping;
    this.flattenEdm = flattenEdm;
  }

  @Override
  public EdmDataServices getMetadata() {
    if (metadata == null) {
      metadata = newEdmGenerator(namespace, typeMapping, ID_PROPNAME, eis, complexTypes).generateEdm(decorator).build();
    }
    return metadata;
  }
  
  public String getContainerName() {
    return containerName;
  }

  protected InMemoryEdmGenerator newEdmGenerator(String namespace, InMemoryTypeMapping typeMapping, String idPropName, Map<String, InMemoryEntityInfo<?>> eis,
          Map<String, InMemoryComplexTypeInfo<?>> complexTypesInfo) {
    return new InMemoryEdmGenerator(namespace, containerName, typeMapping, ID_PROPNAME, eis, complexTypesInfo, this.flattenEdm);
  }

  @Override
  public MetadataProducer getMetadataProducer() {
    return metadataProducer;
  }

  @Override
  public void close() {

  }

  public void setIncludeNullPropertyValues(boolean value) { this.includeNullPropertyValues = value; }
  
  /**
   * register a POJO class as an EdmComplexType.
   * 
   * @param complexTypeClass    The POJO Class
   * @param typeName            The name of the EdmComplexType
   */
  public <TEntity> void registerComplexType(Class<TEntity> complexTypeClass, String typeName) {
    registerComplexType(complexTypeClass, typeName,
        new EnumsAsStringsPropertyModelDelegate(new BeanBasedPropertyModel(complexTypeClass, this.flattenEdm)));
  }
  
  public <TEntity> void registerComplexType(Class<TEntity> complexTypeClass, String typeName, PropertyModel propertyModel) {
    InMemoryComplexTypeInfo<TEntity> i = new InMemoryComplexTypeInfo<TEntity>();
    i.typeName = (null == typeName) ? complexTypeClass.getSimpleName() : typeName;
    i.entityClass = complexTypeClass;
    i.propertyModel = propertyModel;
    
    complexTypes.put(i.typeName, i);
    metadata = null;
  }
  
  /**
   * Registers a new entity based on a POJO, with support for composite keys.
   *
   * @param entityClass  the class of the entities that are to be stored in the set
   * @param entitySetName  the alias the set will be known by; this is what is used in the OData url
   * @param get  a function to iterate over the elements in the set
   * @param keys  one or more keys for the entity
   */
  public <TEntity> void register(Class<TEntity> entityClass, String entitySetName, Func<Iterable<TEntity>> get, String... keys) {
    register(entityClass, entitySetName, entitySetName, get, keys);
  }

  /**
   * Registers a new entity based on a POJO, with support for composite keys.
   *
   * @param entityClass  the class of the entities that are to be stored in the set
   * @param entitySetName  the alias the set will be known by; this is what is used in the OData url
   * @param entityTypeName  type name of the entity
   * @param get  a function to iterate over the elements in the set
   * @param keys  one or more keys for the entity
   */
  public <TEntity> void register(Class<TEntity> entityClass, String entitySetName, String entityTypeName, Func<Iterable<TEntity>> get, String... keys) {
    PropertyModel model = new BeanBasedPropertyModel(entityClass, this.flattenEdm);
    model = new EnumsAsStringsPropertyModelDelegate(model);
    register(entityClass, model, entitySetName, entityTypeName, get, keys);
  }

  /**
   * Registers a new entity set based on a POJO type using the default property model.
   */
  public <TEntity, TKey> void register(Class<TEntity> entityClass, Class<TKey> keyClass, String entitySetName, Func<Iterable<TEntity>> get, Func1<TEntity, TKey> id) {
    PropertyModel model = new BeanBasedPropertyModel(entityClass, this.flattenEdm);
    model = new EnumsAsStringsPropertyModelDelegate(model);
    model = new EntityIdFunctionPropertyModelDelegate<TEntity, TKey>(model, ID_PROPNAME, keyClass, id);
    register(entityClass, model, entitySetName, get, ID_PROPNAME);
  }

  /**
   * Registers a new entity set based on a POJO type and a property model.
   *
   * @param entityClass  the class of the entities that are to be stored in the set
   * @param propertyModel a way to get/set properties on the POJO
   * @param entitySetName  the alias the set will be known by; this is what is used in the ODATA URL
   * @param get  a function to iterate over the elements in the set
   * @param keys  one or more keys for the entity
   */
  public <TEntity, TKey> void register(
      Class<TEntity> entityClass,
      PropertyModel propertyModel,
      String entitySetName,
      Func<Iterable<TEntity>> get,
      String... keys) {
    register(entityClass, propertyModel, entitySetName, entitySetName, get, keys);
  }

  public <TEntity> void register(
      final Class<TEntity> entityClass,
      final PropertyModel propertyModel,
      final String entitySetName,
      final String entityTypeName,
      final Func<Iterable<TEntity>> get,
      final String... keys) {

    InMemoryEntityInfo<TEntity> ei = new InMemoryEntityInfo<TEntity>();
    ei.entitySetName = entitySetName;
    ei.entityTypeName = entityTypeName;
    ei.properties = propertyModel;
    ei.get = get;
    ei.keys = keys;
    ei.entityClass = entityClass;
    ei.hasStream = OAtomStreamEntity.class.isAssignableFrom(entityClass);

    ei.id = new Func1<Object, HashMap<String, Object>>() {
      @Override
      public HashMap<String, Object> apply(Object input) {
        HashMap<String, Object> values = new HashMap<String, Object>();
        for (String key : keys) {
          values.put(key, eis.get(entitySetName).properties.getPropertyValue(input, key));
        }
        return values;
      }
    };

    eis.put(entitySetName, ei);
    metadata = null;
  }

  protected InMemoryComplexTypeInfo<?> findComplexTypeInfoForClass(Class<?> clazz) {
    for (InMemoryComplexTypeInfo<?> typeInfo : this.complexTypes.values()) {
      if (typeInfo.entityClass.equals(clazz)) {
        return typeInfo;
      }
    }
    
    return null;
  }
  
  protected InMemoryEntityInfo<?> findEntityInfoForClass(Class<?> clazz) {
    for (InMemoryEntityInfo<?> typeInfo : this.eis.values()) {
      if (typeInfo.entityClass.equals(clazz)) {
        return typeInfo;
      }
    }
    
    return null;
  }
  
  /**
   * transforms a POJO into a list of OProperties based on a given
   * EdmStructuralType.
   *
   * @param obj the POJO to transform
   * @param propertyModel the PropertyModel to use to access POJO class
   * structure and values.
   * @param structuralType the EdmStructuralType
   * @param properties put properties into this list.
   */
  protected void addPropertiesFromObject(Object obj, PropertyModel propertyModel, EdmStructuralType structuralType, List<OProperty<?>> properties, PropertyPathHelper pathHelper) {
    //System.out.println("addPropertiesFromObject: " + obj.getClass().getName());
    for (Iterator<EdmProperty> it = structuralType.getProperties().iterator(); it.hasNext();) {
      EdmProperty property = it.next();

      // $select projections not allowed for complex types....hmmh...why?
      if (structuralType instanceof EdmEntityType && !pathHelper.isSelected(property.getName())) {
        continue;
      }

      Object value = propertyModel.getPropertyValue(obj, property.getName());
      //System.out.println("  prop: " + property.getName() + " val: " + value);
      if (value == null && !this.includeNullPropertyValues) {
        // this is not permitted by the spec but makes debugging wide entity types
        // much easier.
        continue;
      }

      if (property.getCollectionKind() == EdmProperty.CollectionKind.NONE) {
        if (property.getType().isSimple()) {
          properties.add(OProperties.simple(property.getName(), (EdmSimpleType) property.getType(), value));
        } else {
          // complex. 
          if (value == null) {
            properties.add(OProperties.complex(property.getName(), (EdmComplexType) property.getType(), null));
          } else {
            Class<?> propType = propertyModel.getPropertyType(property.getName());
            InMemoryComplexTypeInfo<?> typeInfo = findComplexTypeInfoForClass(propType);
            if (null == typeInfo) {
              continue;
            }
            List<OProperty<?>> cprops = new ArrayList<OProperty<?>>();
            addPropertiesFromObject(value, typeInfo.getPropertyModel(), (EdmComplexType) property.getType(), cprops, pathHelper);
            properties.add(OProperties.complex(property.getName(), (EdmComplexType) property.getType(), cprops));
          }
        }
      } else {
        // collection.
        Iterable<?> values = propertyModel.getCollectionValue(obj, property.getName());
        OCollection.Builder<OObject> b = OCollections.newBuilder(property.getType());
        if (values != null) {
          Class<?> propType = propertyModel.getCollectionElementType(property.getName());
          InMemoryComplexTypeInfo<?> typeInfo = property.getType().isSimple() ? null : findComplexTypeInfoForClass(propType);
          if ((!property.getType().isSimple()) && null == typeInfo) {
            continue;
          }
          for (Object v : values) {
            if (property.getType().isSimple()) {
              b.add(OSimpleObjects.create((EdmSimpleType) property.getType(), v));
            } else {
              List<OProperty<?>> cprops = new ArrayList<OProperty<?>>();
              addPropertiesFromObject(v, typeInfo.getPropertyModel(), (EdmComplexType) property.getType(), cprops, pathHelper);
              b.add(OComplexObjects.create((EdmComplexType) property.getType(), cprops));
            }
          }
        }
        properties.add(OProperties.collection(property.getName(),
                // hmmmh...is something is wrong here if I have to create a new EdmCollectionType?
                new EdmCollectionType(EdmProperty.CollectionKind.Collection,
                property.getType()), b.build()));
      }
    }
    //System.out.println("done addPropertiesFromObject: " + obj.getClass().getName());
  }
  
  protected OEntity toOEntity(EdmEntitySet ees, Object obj, PropertyPathHelper pathHelper) {

    InMemoryEntityInfo<?> ei = this.findEntityInfoForClass(obj.getClass()); //  eis.get(ees.getName());
    final List<OLink> links = new ArrayList<OLink>();
    final List<OProperty<?>> properties = new ArrayList<OProperty<?>>();

    Map<String, Object> keyKVPair = new HashMap<String, Object>();
    for (String key : ei.getKeys()) {
      Object keyValue = ei.getPropertyModel().getPropertyValue(obj, key);
      keyKVPair.put(key, keyValue);
    }

    // "regular" properties
    addPropertiesFromObject(obj, ei.getPropertyModel(), ees.getType(), properties, pathHelper);

    // navigation properties
    EdmEntityType edmEntityType = ees.getType();

    for (final EdmNavigationProperty navProp : ees.getType().getNavigationProperties()) {

      if (!pathHelper.isSelected(navProp.getName())) {
        continue;
      }

      if (!pathHelper.isExpanded(navProp.getName())) {
        // defer
        if (navProp.getToRole().getMultiplicity() == EdmMultiplicity.MANY) {
          links.add(OLinks.relatedEntities(null, navProp.getName(), null));
        } else {
          links.add(OLinks.relatedEntity(null, navProp.getName(), null));
        }
      } else {
        // inline
        pathHelper.navigate(navProp.getName());
        if (navProp.getToRole().getMultiplicity() == EdmMultiplicity.MANY) {
          List<OEntity> relatedEntities = new ArrayList<OEntity>();

          EdmEntitySet relEntitySet = null;

          for (final Object entity : getRelatedPojos(navProp, obj, ei)) {
            if (relEntitySet == null) {
              InMemoryEntityInfo<?> oei = this.findEntityInfoForClass(entity.getClass());
              relEntitySet = getMetadata().getEdmEntitySet(oei.getEntitySetName());
            }

            relatedEntities.add(toOEntity(relEntitySet, entity, pathHelper));
          }
          
          // relation and href will be filled in later for atom or json
          links.add(OLinks.relatedEntitiesInline(null, navProp.getName(), null, relatedEntities));
        } else {
          final Object entity = ei.getPropertyModel().getPropertyValue(obj, navProp.getName());
          OEntity relatedEntity = null;

          if (entity != null) {
            InMemoryEntityInfo<?> oei = this.findEntityInfoForClass(entity.getClass());
            EdmEntitySet relEntitySet = getMetadata().getEdmEntitySet(oei.getEntitySetName());
            relatedEntity = toOEntity(relEntitySet, entity, pathHelper);
          }
          links.add(OLinks.relatedEntityInline(null, navProp.getName(), null, relatedEntity));
        }

        pathHelper.popPath();
      }
    }

    return OEntities.create(ees, OEntityKey.create(keyKVPair), properties, links, obj);
  }
  
  protected Iterable<?> getRelatedPojos(EdmNavigationProperty navProp, Object srcObject, InMemoryEntityInfo<?> srcInfo) {
    if (navProp.getToRole().getMultiplicity() == EdmMultiplicity.MANY) {
      Iterable<?> i =  srcInfo.getPropertyModel().getCollectionValue(srcObject, navProp.getName());
      return null == i ? Collections.EMPTY_LIST : i;
    } else {
      // can be null
      return Collections.singletonList(srcInfo.getPropertyModel().getPropertyValue(srcObject, navProp.getName()));
    }
  }

  private static Predicate1<Object> filterToPredicate(final BoolCommonExpression filter, final PropertyModel properties) {
    return new Predicate1<Object>() {
      public boolean apply(Object input) {
        return InMemoryEvaluation.evaluate(filter, input, properties);
      }
    };
  }

  @Override
  public EntitiesResponse getEntities(String entitySetName, final QueryInfo queryInfo) {
    final EdmEntitySet ees = getMetadata().getEdmEntitySet(entitySetName);
    final InMemoryEntityInfo<?> ei = eis.get(entitySetName);

    Enumerable<Object> objects = Enumerable.create(ei.get.apply()).cast(Object.class);

    // apply filter
    if (queryInfo != null && queryInfo.filter != null) {
      objects = objects.where(filterToPredicate(queryInfo.filter, ei.properties));
    }

    // compute inlineCount, must be done after applying filter
    Integer inlineCount = null;
    if (queryInfo != null && queryInfo.inlineCount == InlineCount.ALLPAGES) {
      objects = Enumerable.create(objects.toList()); // materialize up front, since we're about to count
      inlineCount = objects.count();
    }

    // apply ordering
    if (queryInfo != null && queryInfo.orderBy != null) {
      objects = orderBy(objects, queryInfo.orderBy, ei.properties);
    }

    // work with oentities
    final PropertyPathHelper pathHelper = new PropertyPathHelper(queryInfo);
    Enumerable<OEntity> entities = objects.select(new Func1<Object, OEntity>() {
      public OEntity apply(Object input) {
        return toOEntity(ees, input, pathHelper);
      }
    });

    // skip records by $skipToken
    if (queryInfo != null && queryInfo.skipToken != null) {
      final Boolean[] skipping = new Boolean[] { true };
      entities = entities.skipWhile(new Predicate1<OEntity>() {
        public boolean apply(OEntity input) {
          if (skipping[0]) {
            String inputKey = input.getEntityKey().toKeyString();
            if (queryInfo.skipToken.equals(inputKey)) skipping[0] = false;
            return true;
          }
          return false;
        }
      });
    }

    // skip records by $skip amount
    if (queryInfo != null && queryInfo.skip != null) {
      entities = entities.skip(queryInfo.skip);
    }

    // apply limit
    int limit = this.maxResults;
    if (queryInfo != null && queryInfo.top != null && queryInfo.top < limit) {
      limit = queryInfo.top;
    }
    entities = entities.take(limit + 1);

    // materialize OEntities
    List<OEntity> entitiesList = entities.toList();

    // determine skipToken if necessary
    String skipToken = null;
    if (entitiesList.size() > limit) {
      entitiesList = Enumerable.create(entitiesList).take(limit).toList();
      skipToken = entitiesList.size() == 0 ? null : Enumerable.create(entitiesList).last().getEntityKey().toKeyString();
    }

    return Responses.entities(entitiesList, ees, inlineCount, skipToken);

  }

  @Override
  public CountResponse getEntitiesCount(String entitySetName, final QueryInfo queryInfo) {
    final EdmEntitySet ees = getMetadata().getEdmEntitySet(entitySetName);
    final InMemoryEntityInfo<?> ei = eis.get(entitySetName);

    Enumerable<Object> objects = Enumerable.create(ei.get.apply()).cast(Object.class);

    // apply filter
    if (queryInfo != null && queryInfo.filter != null) {
      objects = objects.where(filterToPredicate(queryInfo.filter, ei.properties));
    }

    // inlineCount is not applicable to $count queries
    if (queryInfo != null && queryInfo.inlineCount == InlineCount.ALLPAGES) {
      throw new UnsupportedOperationException("$inlinecount cannot be applied to the resource segment '$count'");
    }

    // ignore ordering for count

    // work with oentities.
    final PropertyPathHelper pathHelper = new PropertyPathHelper(queryInfo);
    Enumerable<OEntity> entities = objects.select(new Func1<Object, OEntity>() {
      public OEntity apply(Object input) {
        return toOEntity(ees, input, pathHelper);
      }
    });

    // skipToken is not applicable to $count queries
    if (queryInfo != null && queryInfo.skipToken != null) {
      throw new UnsupportedOperationException("Skip tokens can only be provided for requests that return collections of entities.");
    }

    // skip records by $skip amount
    // http://services.odata.org/Northwind/Northwind.svc/Customers/$count/?$skip=5
    if (queryInfo != null && queryInfo.skip != null) {
      entities = entities.skip(queryInfo.skip);
    }

    // apply $top.  maxResults is not applicable to $count but $top is.
    // http://services.odata.org/Northwind/Northwind.svc/Customers/$count/?$top=55
    int limit = Integer.MAX_VALUE;
    if (queryInfo != null && queryInfo.top != null && queryInfo.top < limit) {
      limit = queryInfo.top;
    }
    entities = entities.take(limit);

    return Responses.count(entities.count());
  }

  private Enumerable<Object> orderBy(Enumerable<Object> iter, List<OrderByExpression> orderBys, final PropertyModel properties) {
    for (final OrderByExpression orderBy : Enumerable.create(orderBys).reverse())
      iter = iter.orderBy(new Comparator<Object>() {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public int compare(Object o1, Object o2) {
          Comparable lhs = (Comparable) InMemoryEvaluation.evaluate(orderBy.getExpression(), o1, properties);
          Comparable rhs = (Comparable) InMemoryEvaluation.evaluate(orderBy.getExpression(), o2, properties);
          return (orderBy.getDirection() == Direction.ASCENDING ? 1 : -1) * lhs.compareTo(rhs);
        }
      });
    return iter;
  }

  @Override
  public EntityResponse getEntity(String entitySetName, final OEntityKey entityKey, EntityQueryInfo queryInfo) {
    final Object rt = getEntityPojo(entitySetName, entityKey, queryInfo);
    if (rt == null) throw new NotFoundException();

    final EdmEntitySet ees = getMetadata().getEdmEntitySet(entitySetName);
    OEntity oe = toOEntity(ees, rt, new PropertyPathHelper(queryInfo));

    return Responses.entity(oe);
  }

  @Override
  public void mergeEntity(String entitySetName, OEntity entity) {
    throw new NotImplementedException();
  }

  @Override
  public void updateEntity(String entitySetName, OEntity entity) {
    throw new NotImplementedException();
  }

  @Override
  public void deleteEntity(String entitySetName, OEntityKey entityKey) {
    throw new NotImplementedException();
  }

  @Override
  public EntityResponse createEntity(String entitySetName, OEntity entity) {
    throw new NotImplementedException();
  }

  @Override
  public EntityResponse createEntity(String entitySetName, OEntityKey entityKey, String navProp, OEntity entity) {
    throw new NotImplementedException();
  }

  @Override
  public BaseResponse getNavProperty(String entitySetName, OEntityKey entityKey, String navProp, QueryInfo queryInfo) {
    EdmEntitySet edmEntitySet = getMetadata().getEdmEntitySet(entitySetName); // throws NotFoundException
    EdmNavigationProperty navProperty = edmEntitySet.getType().findNavigationProperty(navProp);
    if (navProperty != null) {
      return getNavProperty(edmEntitySet, entityKey, navProperty, queryInfo);
    }
    
    // not a NavigationProperty:
    
    EdmProperty edmProperty = edmEntitySet.getType().findProperty(navProp);
    if (edmProperty == null)
      throw new NotFoundException("Property " + navProp + " is not found");
    // currently only simple types are supported
    EdmType edmType = edmProperty.getType();
    
    if (!edmType.isSimple())
      throw new NotImplementedException("Only simple types are supported. Property type is '" + edmType.getFullyQualifiedTypeName() + "'");

    // get property value...
    InMemoryEntityInfo<?> entityInfo = eis.get(entitySetName);
    Object target = getEntityPojo(entitySetName, entityKey, queryInfo);
    Object propertyValue = entityInfo.properties.getPropertyValue(target, navProp);
    // ... and create OProperty
    OProperty<?> property = OProperties.simple(navProp, (EdmSimpleType<?>) edmType, propertyValue);

    return Responses.property(property);
  }
  
  protected EdmEntitySet findEntitySetForNavProperty(EdmNavigationProperty navProp) {
    EdmEntityType et = navProp.getToRole().getType();
    // assumes one set per type...
    for (EdmEntitySet set : this.getMetadata().getEntitySets()) {
      if (set.getType().equals(et)) {
        return set;
      }
    }
    return null;
  }

  /**
   * gets the entity(s) on the target end of a NavigationProperty
   * @param set         the entityset of the source entity
   * @param entityKey   the key of the source entity
   * @param navProp     the navigation property
   * @param queryInfo   the query information
   * @return a BaseResponse with either a single Entity (can be null) or a set of entities.
   */
  protected BaseResponse getNavProperty(EdmEntitySet set, OEntityKey entityKey, EdmNavigationProperty navProp, QueryInfo queryInfo) {
    
    // First, get the source POJO.  We must create a queryInfo that expands navPropName
    // because the given queryInfo is only correct in the context of the related entities.
    Object obj = getEntityPojo(set.getName(), entityKey,
            new QueryInfo.Builder().setExpand(Collections.singletonList(Expression.simpleProperty(navProp.getName()))).build()); // throw NotFoundException

    Iterable<?> relatedPojos = this.getRelatedPojos(navProp, obj, this.findEntityInfoForClass(obj.getClass()));
    PropertyPathHelper pathHelper = new PropertyPathHelper(queryInfo);

    EdmEntitySet targetEntitySet = findEntitySetForNavProperty(navProp);
                       
    if (navProp.getToRole().getMultiplicity() == EdmMultiplicity.MANY) {
      List<OEntity> relatedEntities = new ArrayList<OEntity>();
      for (Object relatedObj : relatedPojos) {
        relatedEntities.add(this.toOEntity(targetEntitySet, relatedObj, pathHelper));
      }
      return Responses.entities(relatedEntities, targetEntitySet, null, null);
    } else {
      return Responses.entity(this.toOEntity(targetEntitySet, relatedPojos.iterator().next(), pathHelper));
    }
  }

  @Override
  public CountResponse getNavPropertyCount(String entitySetName, OEntityKey entityKey, String navProp, QueryInfo queryInfo) {
    throw new NotImplementedException();
  }

  @Override
  public EntityIdResponse getLinks(OEntityId sourceEntity, String targetNavProp) {
    throw new NotImplementedException();
  }

  @Override
  public void createLink(OEntityId sourceEntity, String targetNavProp, OEntityId targetEntity) {
    throw new NotImplementedException();
  }

  @Override
  public void updateLink(OEntityId sourceEntity, String targetNavProp, OEntityKey oldTargetEntityKey, OEntityId newTargetEntity) {
    throw new NotImplementedException();
  }

  @Override
  public void deleteLink(OEntityId sourceEntity, String targetNavProp, OEntityKey targetEntityKey) {
    throw new NotImplementedException();
  }

  @Override
  public BaseResponse callFunction(EdmFunctionImport name, java.util.Map<String, OFunctionParameter> params, QueryInfo queryInfo) {
    throw new NotImplementedException();
  }

  /**
   * given an entity set and an entity key, return the pojo that is that entity instance.
   * The default implementation iterates over the entire set of pojos to find the
   * desired instance.
   * 
   * @param entitySetName
   * @param entityKey
   * @param queryInfo - custom query options may be useful
   * @return 
   */
  @SuppressWarnings("unchecked")
  protected Object getEntityPojo(String entitySetName, final OEntityKey entityKey, QueryInfo queryInfo) {
    final InMemoryEntityInfo<?> ei = eis.get(entitySetName);

    final String[] keyList = ei.keys;

    Iterable<Object> iter = (Iterable<Object>) ei.get.apply();

    final Object rt = Enumerable.create(iter).firstOrNull(new Predicate1<Object>() {
      public boolean apply(Object input) {
        HashMap<String, Object> idObjectMap = ei.id.apply(input);

        if (keyList.length == 1) {
          Object idValue = entityKey.asSingleValue();
          return idObjectMap.get(keyList[0]).equals(idValue);
        } else if (keyList.length > 1) {
          for (String key : keyList) {
            Object curValue = null;
            Iterator<OProperty<?>> keyProps = entityKey.asComplexProperties().iterator();
            while (keyProps.hasNext()) {
              OProperty<?> keyProp = keyProps.next();
              if (keyProp.getName().equalsIgnoreCase(key)) {
                curValue = keyProp.getValue();
              }
            }
            if (curValue == null) {
              return false;
            } else if (!idObjectMap.get(key).equals(curValue)) {
              return false;
            }
          }
          return true;
        } else {
          return false;
        }
      }
    });
    return rt;
  }
  
  private enum TriggerType { Before, After };
  protected void fireUnmarshalEvent(Object pojo, OStructuralObject sobj, TriggerType ttype) 
          throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
     try {
      Method m = pojo.getClass().getMethod(ttype == TriggerType.Before ? "beforeOEntityUnmarshal" : "afterOEntityUnmarshal", OStructuralObject.class);
      if (null != m) {
        m.invoke(pojo, sobj);
      }
    } catch (NoSuchMethodException ex) {
    }
  }
  
  /**
   * transform an OComplexObject into a POJO of the given class
   * 
   * @param <T>
   * @param entity
   * @param pojoClass
   * @return
   * @throws InstantiationException
   * @throws IllegalAccessException 
   */
  public <T> T toPojo(OComplexObject entity, Class<T> pojoClass) throws InstantiationException, 
          IllegalAccessException, IllegalArgumentException, InvocationTargetException {
    InMemoryComplexTypeInfo<?> e = this.findComplexTypeInfoForClass(pojoClass);

    T pojo = fillInPojo(entity, this.getMetadata().findEdmComplexType(
              this.namespace + "." + e.getTypeName()), e.getPropertyModel(), pojoClass);
    
    fireUnmarshalEvent(pojo, entity, TriggerType.After);
    return pojo;  
  }

  /**
   * populate a new POJO instance of type pojoClass using data fromn the given structural object
   * 
   * @param <T>
   * @param sobj
   * @param stype
   * @param propertyModel
   * @param pojoClass
   * @return
   * @throws InstantiationException
   * @throws IllegalAccessException 
   */
  protected <T> T fillInPojo(OStructuralObject sobj, EdmStructuralType stype, PropertyModel propertyModel,
          Class<T> pojoClass) throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {

    T pojo = pojoClass.newInstance();
    fireUnmarshalEvent(pojo, sobj, TriggerType.Before);
    
    for (Iterator<EdmProperty> it = stype.getProperties().iterator(); it.hasNext();) {
      EdmProperty property = it.next();
      Object value = null;
      try {
        value = sobj.getProperty(property.getName()).getValue();
      } catch(Exception ex) {
        // property not define on object
        if (property.isNullable()) {
          continue;
        } else {
          throw new RuntimeException("missing required property " + property.getName());
        }
      }

      if (property.getCollectionKind() == EdmProperty.CollectionKind.NONE) {
        if (property.getType().isSimple()) {
          // call the setter.
          propertyModel.setPropertyValue(pojo, property.getName(), value);
        } else {
          // complex.
          // hmmh, value is a Collection<OProperty<?>>...why is it not an OComplexObject.
          
          propertyModel.setPropertyValue(
                  pojo,
                  property.getName(),
                  null == value 
                    ? null 
                    : toPojo(
                        OComplexObjects.create((EdmComplexType)property.getType(), (List<OProperty<?>>)value), 
                        propertyModel.getPropertyType(property.getName())));
        }
      } else {
        // collection. 
        OCollection<? extends OObject> collection = (OCollection<? extends OObject>) value;
        List<Object> pojos = new ArrayList<Object>();
        for (OObject item : collection) {
          if (collection.getType().isSimple()) {
            pojos.add(((OSimpleObject)item).getValue());
          } else {
            // turn OComplexObject into a pojo
            pojos.add(toPojo((OComplexObject) item, propertyModel.getCollectionElementType(property.getName())));
          }
        }
        propertyModel.setCollectionValue(pojo, property.getName(), pojos);
      }
    }

    return pojo;
  }

  /*
   * Design note:
   * toPojo is functionality that is useful on both the producer and consumer side.
   * I'm putting it in the producer class for now although I suspect there is a
   * more elegant design that factors out POJO Classes and PropertyModels into
   * some kind of "PojoModelDefinition" class.  The producer side would then
   * layer and extended definition that defined how the PojoModelDefinition maps
   * to entity sets and such.
   * 
   * with all that said, hopefully this start is useful.  I'm going to use it on
   * our producer side for now to handle createEntity payloads.
   */
  
  /**
   * transform the given entity into a POJO of type pojoClass.
   * 
   * @param <T>
   * @param entity
   * @param pojoClass
   * @return
   * @throws InstantiationException
   * @throws IllegalAccessException 
   */
  public <T> T toPojo(OEntity entity, Class<T> pojoClass) throws InstantiationException, 
          IllegalAccessException, IllegalArgumentException, InvocationTargetException {

    InMemoryEntityInfo<?> e = this.findEntityInfoForClass(pojoClass);

    // so, how is this going to work?
    // we have the PropertyModel available.  We can lookup the EdmStructuredType if necessary.

    EdmEntitySet entitySet = this.getMetadata().findEdmEntitySet(e.getEntitySetName());

    T pojo = fillInPojo(entity, entitySet.getType(), e.getPropertyModel(), pojoClass);

    // nav props
    for (Iterator<EdmNavigationProperty> it = entitySet.getType().getNavigationProperties().iterator(); it.hasNext();) {
      EdmNavigationProperty np = it.next();
      OLink link = null;
      try {
        link = entity.getLink(np.getName(), OLink.class);
      } catch(IllegalArgumentException nolinkex) {
        continue;
      }

      if (link.isInline()) {
        if (link.isCollection()) {
          List<Object> pojos = new ArrayList<Object>();
          for (OEntity relatedEntity : link.getRelatedEntities()) {
            pojos.add(toPojo(relatedEntity, e.getPropertyModel().getCollectionElementType(np.getName())));
          }
          e.getPropertyModel().setCollectionValue(pojo, np.getName(), pojos);
        } else {
          e.getPropertyModel().setPropertyValue(pojo, np.getName(), 
                  toPojo(link.getRelatedEntity(), e.getPropertyModel().getPropertyType(np.getName())));
        }
      } // else ignore deferred links.
    }

    fireUnmarshalEvent(pojo, entity, TriggerType.After);
    return pojo;
  }
}

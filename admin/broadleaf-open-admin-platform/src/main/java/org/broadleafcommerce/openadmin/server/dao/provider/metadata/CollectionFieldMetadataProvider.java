/*
 * #%L
 * BroadleafCommerce Open Admin Platform
 * %%
 * Copyright (C) 2009 - 2016 Broadleaf Commerce
 * %%
 * Licensed under the Broadleaf Fair Use License Agreement, Version 1.0
 * (the "Fair Use License" located  at http://license.broadleafcommerce.org/fair_use_license-1.0.txt)
 * unless the restrictions on use therein are violated and require payment to Broadleaf in which case
 * the Broadleaf End User License Agreement (EULA), Version 1.1
 * (the "Commercial License" located at http://license.broadleafcommerce.org/commercial_license-1.1.txt)
 * shall apply.
 * 
 * Alternatively, the Commercial License may be replaced with a mutually agreed upon license (the "Custom License")
 * between you and Broadleaf Commerce. You may not use this file except in compliance with the applicable license.
 * #L%
 */
package org.broadleafcommerce.openadmin.server.dao.provider.metadata;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.broadleafcommerce.common.presentation.AdminPresentationCollection;
import org.broadleafcommerce.common.presentation.AdminPresentationOperationTypes;
import org.broadleafcommerce.common.presentation.FieldValueConfiguration;
import org.broadleafcommerce.common.presentation.client.AddMethodType;
import org.broadleafcommerce.common.presentation.client.ForeignKeyRestrictionType;
import org.broadleafcommerce.common.presentation.client.OperationType;
import org.broadleafcommerce.common.presentation.client.PersistencePerspectiveItemType;
import org.broadleafcommerce.common.presentation.override.AdminPresentationMergeEntry;
import org.broadleafcommerce.common.presentation.override.AdminPresentationMergeOverride;
import org.broadleafcommerce.common.presentation.override.AdminPresentationMergeOverrides;
import org.broadleafcommerce.common.presentation.override.PropertyType;
import org.broadleafcommerce.openadmin.dto.BasicCollectionMetadata;
import org.broadleafcommerce.openadmin.dto.FieldMetadata;
import org.broadleafcommerce.openadmin.dto.ForeignKey;
import org.broadleafcommerce.openadmin.dto.PersistencePerspective;
import org.broadleafcommerce.openadmin.dto.override.FieldMetadataOverride;
import org.broadleafcommerce.openadmin.dto.override.MetadataOverride;
import org.broadleafcommerce.openadmin.server.dao.FieldInfo;
import org.broadleafcommerce.openadmin.server.dao.provider.metadata.request.AddFieldMetadataRequest;
import org.broadleafcommerce.openadmin.server.dao.provider.metadata.request.OverrideViaAnnotationRequest;
import org.broadleafcommerce.openadmin.server.dao.provider.metadata.request.OverrideViaXmlRequest;
import org.broadleafcommerce.openadmin.server.service.type.MetadataProviderResponse;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Jeff Fischer
 */
@Component("blCollectionFieldMetadataProvider")
@Scope("prototype")
public class CollectionFieldMetadataProvider extends AdvancedCollectionFieldMetadataProvider {

    private static final Log LOG = LogFactory.getLog(CollectionFieldMetadataProvider.class);

    protected boolean canHandleFieldForConfiguredMetadata(AddFieldMetadataRequest addMetadataRequest, Map<String, FieldMetadata> metadata) {
        AdminPresentationCollection annot = addMetadataRequest.getRequestedField().getAnnotation(AdminPresentationCollection.class);
        return annot != null;
    }

    protected boolean canHandleAnnotationOverride(OverrideViaAnnotationRequest overrideViaAnnotationRequest, Map<String, FieldMetadata> metadata) {
        return overrideViaAnnotationRequest.getRequestedEntity().getAnnotation(AdminPresentationMergeOverrides.class) != null;
    }

    @Override
    public MetadataProviderResponse addMetadata(AddFieldMetadataRequest addMetadataRequest, Map<String, FieldMetadata> metadata) {
        if (!canHandleFieldForConfiguredMetadata(addMetadataRequest, metadata)) {
            return MetadataProviderResponse.NOT_HANDLED;
        }
        AdminPresentationCollection annot = addMetadataRequest.getRequestedField().getAnnotation(AdminPresentationCollection
                .class);
        FieldInfo info = buildFieldInfo(addMetadataRequest.getRequestedField());
        FieldMetadataOverride override = constructBasicCollectionMetadataOverride(annot);
        buildCollectionMetadata(addMetadataRequest.getParentClass(), addMetadataRequest.getTargetClass(),
                metadata, info, override, addMetadataRequest.getPrefix());
        setClassOwnership(addMetadataRequest.getParentClass(), addMetadataRequest.getTargetClass(), metadata, info);
        return MetadataProviderResponse.HANDLED;
    }

    @Override
    public MetadataProviderResponse overrideViaAnnotation(OverrideViaAnnotationRequest overrideViaAnnotationRequest, Map<String, FieldMetadata> metadata) {
        if (!canHandleAnnotationOverride(overrideViaAnnotationRequest, metadata)) {
            return MetadataProviderResponse.NOT_HANDLED;
        }

        AdminPresentationMergeOverrides myMergeOverrides = overrideViaAnnotationRequest.getRequestedEntity().getAnnotation(AdminPresentationMergeOverrides.class);
        if (myMergeOverrides != null) {
            for (AdminPresentationMergeOverride override : myMergeOverrides.value()) {
                String propertyName = override.name();
                Map<String, FieldMetadata> loopMap = new HashMap<String, FieldMetadata>();
                loopMap.putAll(metadata);
                for (Map.Entry<String, FieldMetadata> entry : loopMap.entrySet()) {
                    if (entry.getKey().startsWith(propertyName) || StringUtils.isEmpty(propertyName)) {
                        FieldMetadata targetMetadata = entry.getValue();
                        if (targetMetadata instanceof BasicCollectionMetadata) {
                            BasicCollectionMetadata serverMetadata = (BasicCollectionMetadata) targetMetadata;
                            if (serverMetadata.getTargetClass() != null) {
                                try {
                                    Class<?> targetClass = Class.forName(serverMetadata.getTargetClass());
                                    Class<?> parentClass = null;
                                    if (serverMetadata.getOwningClass() != null) {
                                        parentClass = Class.forName(serverMetadata.getOwningClass());
                                    }
                                    String fieldName = serverMetadata.getFieldName();
                                    Field field = overrideViaAnnotationRequest.getDynamicEntityDao().getFieldManager()
                                                .getField(targetClass, fieldName);
                                    Map<String, FieldMetadata> temp = new HashMap<String, FieldMetadata>(1);
                                    temp.put(field.getName(), serverMetadata);
                                    FieldInfo info = buildFieldInfo(field);
                                    FieldMetadataOverride fieldMetadataOverride = overrideCollectionMergeMetadata(override);
                                    if (serverMetadata.getExcluded() != null && serverMetadata.getExcluded() &&
                                            (fieldMetadataOverride.getExcluded() == null || fieldMetadataOverride.getExcluded())) {
                                        continue;
                                    }
                                    buildCollectionMetadata(parentClass, targetClass, temp, info, fieldMetadataOverride, overrideViaAnnotationRequest.getPrefix());
                                    serverMetadata = (BasicCollectionMetadata) temp.get(field.getName());
                                    metadata.put(entry.getKey(), serverMetadata);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    }
                }
            }
        }

        return MetadataProviderResponse.HANDLED;
    }

    @Override
    public MetadataProviderResponse overrideViaXml(OverrideViaXmlRequest overrideViaXmlRequest, Map<String, FieldMetadata> metadata) {
        Map<String, MetadataOverride> overrides = getTargetedOverride(overrideViaXmlRequest.getDynamicEntityDao(), overrideViaXmlRequest.getRequestedConfigKey(), overrideViaXmlRequest.getRequestedCeilingEntity());
        if (overrides != null) {
            for (String propertyName : overrides.keySet()) {
                MetadataOverride localMetadata = overrides.get(propertyName);
                if (localMetadata instanceof FieldMetadataOverride) {
                    FieldMetadataOverride localFieldMetadata = (FieldMetadataOverride) localMetadata;
                    for (String key : metadata.keySet()) {
                        if (key.equals(propertyName)) {
                            try {
                                if (metadata.get(key) instanceof BasicCollectionMetadata) {
                                    BasicCollectionMetadata serverMetadata = (BasicCollectionMetadata) metadata.get(key);
                                    if (serverMetadata.getTargetClass() != null) {
                                        Class<?> targetClass = Class.forName(serverMetadata.getTargetClass());
                                        Class<?> parentClass = null;
                                        if (serverMetadata.getOwningClass() != null) {
                                            parentClass = Class.forName(serverMetadata.getOwningClass());
                                        }
                                        String fieldName = serverMetadata.getFieldName();
                                        Field field = overrideViaXmlRequest.getDynamicEntityDao().getFieldManager().getField(targetClass, fieldName);
                                        Map<String, FieldMetadata> temp = new HashMap<String, FieldMetadata>(1);
                                        temp.put(field.getName(), serverMetadata);
                                        FieldInfo info = buildFieldInfo(field);
                                        buildCollectionMetadata(parentClass, targetClass, temp, info, localFieldMetadata, overrideViaXmlRequest.getPrefix());
                                        serverMetadata = (BasicCollectionMetadata) temp.get(field.getName());
                                        metadata.put(key, serverMetadata);
                                        if (overrideViaXmlRequest.getParentExcluded()) {
                                            if (LOG.isDebugEnabled()) {
                                                LOG.debug("applyCollectionMetadataOverrides:Excluding " + key + "because parent is marked as excluded.");
                                            }
                                            serverMetadata.setExcluded(true);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
            }
        }
        return MetadataProviderResponse.HANDLED;
    }

    protected FieldMetadataOverride overrideCollectionMergeMetadata(AdminPresentationMergeOverride merge) {
        FieldMetadataOverride fieldMetadataOverride = new FieldMetadataOverride();
        Map<String, AdminPresentationMergeEntry> overrideValues = getAdminPresentationEntries(merge.mergeEntries());
        for (Map.Entry<String, AdminPresentationMergeEntry> entry : overrideValues.entrySet()) {
            String stringValue = entry.getValue().overrideValue();
            if (entry.getKey().equals(PropertyType.AdminPresentationCollection.ADDTYPE)) {
                fieldMetadataOverride.setAddType(OperationType.valueOf(stringValue));
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.SELECTIZEVISIBLEFIELD)) {
                fieldMetadataOverride.setSelectizeVisibleField(stringValue);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.CURRENCYCODEFIELD)) {
                fieldMetadataOverride.setCurrencyCodeField(stringValue);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.CUSTOMCRITERIA)) {
                fieldMetadataOverride.setCustomCriteria(entry.getValue().stringArrayOverrideValue());
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.EXCLUDED)) {
                fieldMetadataOverride.setExcluded(StringUtils.isEmpty(stringValue) ? entry.getValue()
                        .booleanOverrideValue() :
                        Boolean.parseBoolean(stringValue));
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.FRIENDLYNAME)) {
                fieldMetadataOverride.setFriendlyName(stringValue);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.ADDFRIENDLYNAME)) {
                fieldMetadataOverride.setAddFriendlyName(stringValue);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.GROUP)) {
                fieldMetadataOverride.setGroup(stringValue);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.MANYTOFIELD)) {
                fieldMetadataOverride.setManyToField(stringValue);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.OPERATIONTYPES)) {
                AdminPresentationOperationTypes operationType = entry.getValue().operationTypes();
                fieldMetadataOverride.setAddType(operationType.addType());
                fieldMetadataOverride.setRemoveType(operationType.removeType());
                fieldMetadataOverride.setUpdateType(operationType.updateType());
                fieldMetadataOverride.setFetchType(operationType.fetchType());
                fieldMetadataOverride.setInspectType(operationType.inspectType());
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.ORDER)) {
                fieldMetadataOverride.setOrder(StringUtils.isEmpty(stringValue) ? entry.getValue().intOverrideValue() :
                        Integer.parseInt(stringValue));
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.MANYTOFIELD)) {
                fieldMetadataOverride.setManyToField(stringValue);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.READONLY)) {
                fieldMetadataOverride.setReadOnly(StringUtils.isEmpty(stringValue) ? entry.getValue()
                        .booleanOverrideValue() :
                        Boolean.parseBoolean(stringValue));
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.SECURITYLEVEL)) {
                fieldMetadataOverride.setSecurityLevel(stringValue);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.SHOWIFPROPERTY)) {
                fieldMetadataOverride.setShowIfProperty(stringValue);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.SHOWIFFIELDEQUALS)) {
                processShowIfFieldEqualsAnnotations(entry.getValue().showIfFieldEquals(), fieldMetadataOverride);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.SORTASCENDING)) {
                fieldMetadataOverride.setSortAscending(StringUtils.isEmpty(stringValue) ? entry.getValue()
                            .booleanOverrideValue() :
                            Boolean.parseBoolean(stringValue));
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.SORTPROPERTY)) {
                fieldMetadataOverride.setSortProperty(stringValue);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.TAB)) {
                fieldMetadataOverride.setTab(stringValue);
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.TABORDER)) {
                fieldMetadataOverride.setTabOrder(StringUtils.isEmpty(stringValue) ? entry.getValue()
                        .intOverrideValue() :
                        Integer.parseInt(stringValue));
            } else if (entry.getKey().equals(PropertyType.AdminPresentationCollection.USESERVERSIDEINSPECTIONCACHE)) {
                fieldMetadataOverride.setUseServerSideInspectionCache(StringUtils.isEmpty(stringValue) ? entry
                        .getValue().booleanOverrideValue() :
                        Boolean.parseBoolean(stringValue));
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Unrecognized type: " + entry.getKey() + ". Not setting on collection field.");
                }
            }
        }

        return fieldMetadataOverride;
    }

    protected FieldMetadataOverride constructBasicCollectionMetadataOverride(AdminPresentationCollection annotColl) {
        if (annotColl != null) {
            FieldMetadataOverride override = new FieldMetadataOverride();
            override.setAddMethodType(annotColl.addType());
            override.setSelectizeVisibleField(annotColl.selectizeVisibleField());
            override.setManyToField(annotColl.manyToField());
            override.setCustomCriteria(annotColl.customCriteria());
            override.setUseServerSideInspectionCache(annotColl.useServerSideInspectionCache());
            override.setExcluded(annotColl.excluded());
            override.setFriendlyName(annotColl.friendlyName());
            override.setAddFriendlyName(annotColl.addFriendlyName());
            override.setReadOnly(annotColl.readOnly());
            override.setSortProperty(annotColl.sortProperty());
            override.setSortAscending(annotColl.sortAscending());
            override.setOrder(annotColl.order());
            override.setTab(annotColl.tab());
            override.setTabOrder(annotColl.tabOrder());
            override.setGroup(annotColl.group());
            override.setSecurityLevel(annotColl.securityLevel());
            override.setAddType(annotColl.operationTypes().addType());
            override.setFetchType(annotColl.operationTypes().fetchType());
            override.setRemoveType(annotColl.operationTypes().removeType());
            override.setUpdateType(annotColl.operationTypes().updateType());
            override.setInspectType(annotColl.operationTypes().inspectType());
            override.setShowIfProperty(annotColl.showIfProperty());
            if (annotColl.showIfFieldEquals().length != 0) {
                processShowIfFieldEqualsAnnotations(annotColl.showIfFieldEquals(), override);
            }
            override.setCurrencyCodeField(annotColl.currencyCodeField());
            override.setLazyFetch(annotColl.lazyFetch());
            override.setManualFetch(annotColl.manualFetch());
            return override;
        }
        throw new IllegalArgumentException("AdminPresentationCollection annotation not found on Field");
    }

    protected void buildCollectionMetadata(Class<?> parentClass, 
            Class<?> targetClass,
            Map<String, FieldMetadata> attributes,
            FieldInfo field,
            FieldMetadataOverride collectionMetadata,
            String prefix) {
        BasicCollectionMetadata serverMetadata = (BasicCollectionMetadata) attributes.get(field.getName());

        Class<?> resolvedClass = parentClass==null?targetClass:parentClass;
        BasicCollectionMetadata metadata;
        if (serverMetadata != null) {
            metadata = serverMetadata;
        } else {
            metadata = new BasicCollectionMetadata();
        }
        metadata.setTargetClass(targetClass.getName());
        metadata.setFieldName(field.getName());
        if (collectionMetadata.getReadOnly() != null) {
            metadata.setMutable(!collectionMetadata.getReadOnly());
        }
        if (collectionMetadata.getAddMethodType() != null) {
            metadata.setAddMethodType(collectionMetadata.getAddMethodType());
        }
        if (collectionMetadata.getShowIfProperty()!=null) {
            metadata.setShowIfProperty(collectionMetadata.getShowIfProperty());
        }
        if (collectionMetadata.getShowIfFieldEquals() != null) {
            metadata.setShowIfFieldEquals(collectionMetadata.getShowIfFieldEquals());
        }
        org.broadleafcommerce.openadmin.dto.OperationTypes dtoOperationTypes = new org.broadleafcommerce.openadmin.dto.OperationTypes(OperationType.BASIC, OperationType.BASIC, OperationType.BASIC, OperationType.BASIC, OperationType.BASIC);
        if (collectionMetadata.getAddType() != null) {
            dtoOperationTypes.setAddType(collectionMetadata.getAddType());
        }
        if (collectionMetadata.getRemoveType() != null) {
            dtoOperationTypes.setRemoveType(collectionMetadata.getRemoveType());
        }
        if (collectionMetadata.getFetchType() != null) {
            dtoOperationTypes.setFetchType(collectionMetadata.getFetchType());
        }
        if (collectionMetadata.getInspectType() != null) {
            dtoOperationTypes.setInspectType(collectionMetadata.getInspectType());
        }
        if (collectionMetadata.getUpdateType() != null) {
            dtoOperationTypes.setUpdateType(collectionMetadata.getUpdateType());
        }

        if (AddMethodType.LOOKUP == metadata.getAddMethodType()
                || AddMethodType.SELECTIZE_LOOKUP == metadata.getAddMethodType()) {
            dtoOperationTypes.setRemoveType(OperationType.NONDESTRUCTIVEREMOVE);
        }

        //don't allow additional non-persistent properties or additional foreign keys for an advanced collection datasource - they don't make sense in this context
        PersistencePerspective persistencePerspective;
        if (serverMetadata != null) {
            persistencePerspective = metadata.getPersistencePerspective();
            persistencePerspective.setOperationTypes(dtoOperationTypes);
        } else {
            persistencePerspective = new PersistencePerspective(dtoOperationTypes, new String[]{}, new ForeignKey[]{});
            metadata.setPersistencePerspective(persistencePerspective);
        }

        String foreignKeyName = null;
        if (serverMetadata != null) {
            foreignKeyName = ((ForeignKey) serverMetadata.getPersistencePerspective().getPersistencePerspectiveItems
                    ().get(PersistencePerspectiveItemType.FOREIGNKEY)).getManyToField();
        }
        if (!StringUtils.isEmpty(collectionMetadata.getManyToField())) {
            foreignKeyName = collectionMetadata.getManyToField();
        }
        if (foreignKeyName == null && !StringUtils.isEmpty(field.getOneToManyMappedBy())) {
            foreignKeyName = field.getOneToManyMappedBy();
        }
        if (foreignKeyName == null && !StringUtils.isEmpty(field.getManyToManyMappedBy())) {
            foreignKeyName = field.getManyToManyMappedBy();
        }
        if (StringUtils.isEmpty(foreignKeyName)) {
            throw new IllegalArgumentException("Unable to infer a ManyToOne field name for the @AdminPresentationCollection annotated field("+field.getName()+"). If not using the mappedBy property of @OneToMany or @ManyToMany, please make sure to explicitly define the manyToField property");
        }

        String sortProperty = null;
        if (serverMetadata != null) {
            sortProperty = ((ForeignKey) serverMetadata.getPersistencePerspective().getPersistencePerspectiveItems().get(PersistencePerspectiveItemType.FOREIGNKEY)).getSortField();
        }
        if (!StringUtils.isEmpty(collectionMetadata.getSortProperty())) {
            sortProperty = collectionMetadata.getSortProperty();
        }

        Boolean isAscending = true;
        if (serverMetadata != null) {
            isAscending = ((ForeignKey) serverMetadata.getPersistencePerspective().getPersistencePerspectiveItems().get(PersistencePerspectiveItemType.FOREIGNKEY)).getSortAscending();
        }
        if (collectionMetadata.isSortAscending()!=null) {
            isAscending = collectionMetadata.isSortAscending();
        }

        if (serverMetadata != null) {
            ForeignKey foreignKey = (ForeignKey) metadata.getPersistencePerspective().getPersistencePerspectiveItems().get(PersistencePerspectiveItemType.FOREIGNKEY);
            foreignKey.setManyToField(foreignKeyName);
            foreignKey.setForeignKeyClass(resolvedClass.getName());
            foreignKey.setMutable(metadata.isMutable());
            foreignKey.setOriginatingField(prefix + field.getName());
            foreignKey.setSortField(sortProperty);
            foreignKey.setSortAscending(isAscending);
        } else {
            ForeignKey foreignKey = new ForeignKey(foreignKeyName, resolvedClass.getName(), null, ForeignKeyRestrictionType.ID_EQ);
            foreignKey.setMutable(metadata.isMutable());
            foreignKey.setOriginatingField(prefix + field.getName());
            foreignKey.setSortField(sortProperty);
            foreignKey.setSortAscending(isAscending);
            persistencePerspective.addPersistencePerspectiveItem(PersistencePerspectiveItemType.FOREIGNKEY, foreignKey);
        }

        String ceiling = null;
        checkCeiling: {
            if (field.getGenericType() instanceof ParameterizedType) {
                try {
                    ParameterizedType pt = (ParameterizedType) field.getGenericType();
                    java.lang.reflect.Type collectionType = pt.getActualTypeArguments()[0];
                    String ceilingEntityName = ((Class<?>) collectionType).getName();
                    ceiling = entityConfiguration.lookupEntityClass(ceilingEntityName).getName();
                    break checkCeiling;
                } catch (NoSuchBeanDefinitionException e) {
                    // We weren't successful at looking at entity configuration to find the type of this collection.
                    // We will continue and attempt to find it via the Hibernate annotations
                }
            }
            if (!StringUtils.isEmpty(field.getOneToManyTargetEntity()) && !void.class.getName().equals(field.getOneToManyTargetEntity())) {
                ceiling = field.getOneToManyTargetEntity();
                break checkCeiling;
            }
            if (!StringUtils.isEmpty(field.getManyToManyTargetEntity()) && !void.class.getName().equals(field.getManyToManyTargetEntity())) {
                ceiling = field.getManyToManyTargetEntity();
                break checkCeiling;
            }
        }
        if (!StringUtils.isEmpty(ceiling)) {
            metadata.setCollectionCeilingEntity(ceiling);
        }

        if (collectionMetadata.getExcluded() != null) {
            if (LOG.isDebugEnabled()) {
                if (collectionMetadata.getExcluded()) {
                    LOG.debug("buildCollectionMetadata:Excluding " + field.getName() + " because it was explicitly declared in config");
                } else {
                    LOG.debug("buildCollectionMetadata:Showing " + field.getName() + " because it was explicitly declared in config");
                }
            }
            metadata.setExcluded(collectionMetadata.getExcluded());
        }

        if (collectionMetadata.getLazyFetch() != null) {
            metadata.setLazyFetch(collectionMetadata.getLazyFetch());
        }
        if (collectionMetadata.getManualFetch() != null) {
            metadata.setManualFetch(collectionMetadata.getManualFetch());
        }

        if (collectionMetadata.getFriendlyName() != null) {
            metadata.setFriendlyName(collectionMetadata.getFriendlyName());
        }
        if (collectionMetadata.getAddFriendlyName() != null) {
            metadata.setAddFriendlyName(collectionMetadata.getAddFriendlyName());
        }

        if (collectionMetadata.getSecurityLevel() != null) {
            metadata.setSecurityLevel(collectionMetadata.getSecurityLevel());
        }
        if (collectionMetadata.getOrder() != null) {
            metadata.setOrder(collectionMetadata.getOrder());
        }

        if (collectionMetadata.getTab() != null) {
            metadata.setTab(collectionMetadata.getTab());
        }
        if (collectionMetadata.getTabOrder() != null) {
            metadata.setTabOrder(collectionMetadata.getTabOrder());
        }

        if (collectionMetadata.getGroup() != null) {
            metadata.setGroup(collectionMetadata.getGroup());
        }

        if (collectionMetadata.getSortProperty() != null) {
            metadata.setSortProperty(collectionMetadata.getSortProperty());
        }

        if (collectionMetadata.getCustomCriteria() != null) {
            metadata.setCustomCriteria(collectionMetadata.getCustomCriteria());
        }

        if (collectionMetadata.getUseServerSideInspectionCache() != null) {
            persistencePerspective.setUseServerSideInspectionCache(collectionMetadata.getUseServerSideInspectionCache());
        }

        if (collectionMetadata.getCurrencyCodeField()!=null) {
            metadata.setCurrencyCodeField(collectionMetadata.getCurrencyCodeField());
        }

        if (collectionMetadata.getSelectizeVisibleField()!=null) {
            metadata.setSelectizeVisibleField(collectionMetadata.getSelectizeVisibleField());
        }

        attributes.put(field.getName(), metadata);
    }

    protected void processShowIfFieldEqualsAnnotations(FieldValueConfiguration[] configurations, FieldMetadataOverride override) {
        if (override.getShowIfFieldEquals() == null) {
            override.setShowIfFieldEquals(new HashMap<String, List<String>>());
        }
        for (FieldValueConfiguration configuration : configurations) {
            override.getShowIfFieldEquals().put(configuration.fieldName(), Arrays.asList(configuration.fieldValues()));
        }
    }

    @Override
    public int getOrder() {
        return FieldMetadataProvider.COLLECTION;
    }
}

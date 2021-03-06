/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.rest.service;

import static org.apache.kylin.metadata.model.DataModelDesc.STATUS_DRAFT;
import static org.apache.kylin.rest.controller2.ModelControllerV2.VALID_MODELNAME;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang.StringUtils;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.metadata.model.DataModelDesc;
import org.apache.kylin.metadata.model.JoinsTree;
import org.apache.kylin.metadata.model.ModelDimensionDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.rest.constant.Constant;
import org.apache.kylin.rest.exception.BadRequestException;
import org.apache.kylin.rest.exception.ForbiddenException;
import org.apache.kylin.rest.msg.Message;
import org.apache.kylin.rest.msg.MsgPicker;
import org.apache.kylin.rest.security.AclPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Created by luwei on 17-4-19.
 */
@Component("modelMgmtServiceV2")
public class ModelServiceV2 extends ModelService {

    private static final Logger logger = LoggerFactory.getLogger(ModelServiceV2.class);

    @Autowired
    @Qualifier("accessService")
    private AccessService accessService;

    @Autowired
    @Qualifier("cubeMgmtServiceV2")
    private CubeServiceV2 cubeServiceV2;

    public DataModelDesc createModelDesc(String projectName, DataModelDesc desc) throws IOException {
        Message msg = MsgPicker.getMsg();

        if (getMetadataManager().getDataModelDesc(desc.getName()) != null) {
            throw new BadRequestException(String.format(msg.getDUPLICATE_MODEL_NAME(), desc.getName()));
        }
        DataModelDesc createdDesc = null;
        String owner = SecurityContextHolder.getContext().getAuthentication().getName();
        createdDesc = getMetadataManager().createDataModelDesc(desc, projectName, owner);

        if (desc.getStatus() == null || !desc.getStatus().equals(STATUS_DRAFT)) {
            accessService.init(createdDesc, AclPermission.ADMINISTRATION);
            ProjectInstance project = getProjectManager().getProject(projectName);
            accessService.inherit(createdDesc, project);
        }
        return createdDesc;
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or hasPermission(#desc, 'ADMINISTRATION') or hasPermission(#desc, 'MANAGEMENT')")
    public void dropModel(DataModelDesc desc) throws IOException {
        Message msg = MsgPicker.getMsg();

        //check cube desc exist
        List<CubeDesc> cubeDescs = getCubeDescManager().listAllDesc();
        for (CubeDesc cubeDesc : cubeDescs) {
            if (cubeDesc.getModelName().equals(desc.getName())) {
                throw new BadRequestException(String.format(msg.getDROP_REFERENCED_MODEL(), cubeDesc.getName()));
            }
        }

        getMetadataManager().dropModel(desc);

        accessService.clean(desc, true);
    }

    public Map<TblColRef, Set<CubeInstance>> getUsedDimCols(String modelName) {
        Map<TblColRef, Set<CubeInstance>> ret = Maps.newHashMap();
        List<CubeInstance> cubeInstances = cubeServiceV2.listAllCubes(null, null, modelName);
        for (CubeInstance cubeInstance : cubeInstances) {
            CubeDesc cubeDesc = cubeInstance.getDescriptor();
            for (TblColRef tblColRef : cubeDesc.listDimensionColumnsIncludingDerived()) {
                if (ret.containsKey(tblColRef)) {
                    ret.get(tblColRef).add(cubeInstance);
                } else {
                    Set<CubeInstance> set = Sets.newHashSet(cubeInstance);
                    ret.put(tblColRef, set);
                }
            }
        }
        return ret;
    }

    public Map<TblColRef, Set<CubeInstance>> getUsedNonDimCols(String modelName) {
        Map<TblColRef, Set<CubeInstance>> ret = Maps.newHashMap();
        List<CubeInstance> cubeInstances = cubeServiceV2.listAllCubes(null, null, modelName);
        for (CubeInstance cubeInstance : cubeInstances) {
            CubeDesc cubeDesc = cubeInstance.getDescriptor();
            Set<TblColRef> tblColRefs = Sets.newHashSet(cubeDesc.listAllColumns());//make a copy
            tblColRefs.removeAll(cubeDesc.listDimensionColumnsIncludingDerived());
            for (TblColRef tblColRef : tblColRefs) {
                if (ret.containsKey(tblColRef)) {
                    ret.get(tblColRef).add(cubeInstance);
                } else {
                    Set<CubeInstance> set = Sets.newHashSet(cubeInstance);
                    ret.put(tblColRef, set);
                }
            }
        }
        return ret;
    }

    public boolean validate(DataModelDesc dataModelDesc) throws IOException {
        Message msg = MsgPicker.getMsg();

        dataModelDesc.init(getConfig(), getMetadataManager().getAllTablesMap(), getMetadataManager().getCcInfoMap());

        List<String> dimCols = new ArrayList<String>();
        List<String> dimAndMCols = new ArrayList<String>();

        List<ModelDimensionDesc> dimensions = dataModelDesc.getDimensions();
        String[] measures = dataModelDesc.getMetrics();

        for (ModelDimensionDesc dim : dimensions) {
            String table = dim.getTable();
            for (String c : dim.getColumns()) {
                dimCols.add(table + "." + c);
            }
        }

        dimAndMCols.addAll(dimCols);

        for (String measure : measures) {
            dimAndMCols.add(measure);
        }

        String modelName = dataModelDesc.getName();
        Set<TblColRef> usedDimCols = getUsedDimCols(modelName).keySet();
        Set<TblColRef> usedNonDimCols = getUsedNonDimCols(modelName).keySet();

        for (TblColRef tblColRef : usedDimCols) {
            if (!dimCols.contains(tblColRef.getTableAlias() + "." + tblColRef.getName()))
                return false;
        }

        for (TblColRef tblColRef : usedNonDimCols) {
            if (!dimAndMCols.contains(tblColRef.getTableAlias() + "." + tblColRef.getName()))
                return false;
        }

        DataModelDesc originDataModelDesc = listAllModels(modelName, null).get(0);

        if (!dataModelDesc.getRootFactTable().equals(originDataModelDesc.getRootFactTable()))
            return false;

        JoinsTree joinsTree = dataModelDesc.getJoinsTree(), originJoinsTree = originDataModelDesc.getJoinsTree();
        if (joinsTree.matchNum(originJoinsTree) != originDataModelDesc.getJoinTables().length + 1)
            return false;

        return true;
    }

    public void validateModelDesc(DataModelDesc modelDesc) {
        Message msg = MsgPicker.getMsg();

        if (modelDesc == null) {
            throw new BadRequestException(msg.getINVALID_MODEL_DEFINITION());
        }

        String modelName = modelDesc.getName();

        if (StringUtils.isEmpty(modelName)) {
            logger.info("Model name should not be empty.");
            throw new BadRequestException(msg.getEMPTY_MODEL_NAME());
        }
        if (!StringUtils.containsOnly(modelName, VALID_MODELNAME)) {
            logger.info("Invalid Model name {}, only letters, numbers and underline supported.", modelDesc.getName());
            throw new BadRequestException(String.format(msg.getINVALID_MODEL_NAME(), modelName));
        }
    }

    public boolean unifyModelDesc(DataModelDesc desc, boolean isDraft) throws IOException {
        boolean createNew = false;
        String name = desc.getName();
        if (isDraft) {
            name += "_draft";
            desc.setName(name);
            desc.setStatus(STATUS_DRAFT);
        } else {
            desc.setStatus(null);
        }

        if (desc.getUuid() == null) {
            desc.setLastModified(0);
            desc.setUuid(UUID.randomUUID().toString());
            return true;
        }

        DataModelDesc youngerSelf = killSameUuid(desc.getUuid(), name, isDraft);
        if (youngerSelf != null) {
            desc.setLastModified(youngerSelf.getLastModified());
        } else {
            createNew = true;
            desc.setLastModified(0);
        }

        return createNew;
    }

    public DataModelDesc killSameUuid(String uuid, String name, boolean isDraft) throws IOException {
        Message msg = MsgPicker.getMsg();

        DataModelDesc youngerSelf = null, official = null;
        boolean rename = false;
        List<DataModelDesc> models = getMetadataManager().getModels();
        for (DataModelDesc model : models) {
            if (model.getUuid().equals(uuid)) {
                boolean toDrop = true;
                boolean sameStatus = sameStatus(model.getStatus(), isDraft);
                if (sameStatus && !model.getName().equals(name)) {
                    rename = true;
                }
                if (sameStatus && model.getName().equals(name)) {
                    youngerSelf = model;
                    toDrop = false;
                }
                if (model.getStatus() == null) {
                    official = model;
                    toDrop = false;
                }
                if (toDrop) {
                    dropModel(model);
                }
            }
        }
        if (official != null && rename) {
            throw new BadRequestException(msg.getMODEL_RENAME());
        }
        return youngerSelf;
    }

    public boolean sameStatus(String status, boolean isDraft) {
        if (status == null || !status.equals(STATUS_DRAFT)) {
            return !isDraft;
        } else {
            return isDraft;
        }
    }

    public DataModelDesc updateModelToResourceStore(DataModelDesc modelDesc, String projectName, boolean createNew, boolean isDraft) throws IOException {
        Message msg = MsgPicker.getMsg();

        if (createNew) {
            createModelDesc(projectName, modelDesc);
        } else {
            try {
                if (!isDraft && !validate(modelDesc)) {
                    throw new BadRequestException(msg.getUPDATE_MODEL_KEY_FIELD());
                }
                modelDesc = updateModelAndDesc(modelDesc);
            } catch (AccessDeniedException accessDeniedException) {
                throw new ForbiddenException(msg.getUPDATE_MODEL_NO_RIGHT());
            }

            if (!modelDesc.getError().isEmpty()) {
                throw new BadRequestException(String.format(msg.getBROKEN_MODEL_DESC(), modelDesc.getName()));
            }
        }
        return modelDesc;
    }
}

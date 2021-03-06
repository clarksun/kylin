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

package org.apache.kylin.rest.controller2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.persistence.ResourceStore;
import org.apache.kylin.common.persistence.ResourceStore.Checkpoint;
import org.apache.kylin.common.util.JsonUtil;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.metadata.MetadataManager;
import org.apache.kylin.metadata.model.DataModelDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.rest.controller.BasicController;
import org.apache.kylin.rest.exception.BadRequestException;
import org.apache.kylin.rest.msg.Message;
import org.apache.kylin.rest.msg.MsgPicker;
import org.apache.kylin.rest.request.ModelRequest;
import org.apache.kylin.rest.response.DataModelDescResponse;
import org.apache.kylin.rest.response.DataModelDescResponse.ModelComparator;
import org.apache.kylin.rest.response.EnvelopeResponse;
import org.apache.kylin.rest.response.GeneralResponse;
import org.apache.kylin.rest.response.ResponseCode;
import org.apache.kylin.rest.service.CacheService;
import org.apache.kylin.rest.service.ModelServiceV2;
import org.apache.kylin.rest.service.ProjectServiceV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.collect.Sets;

/**
 * ModelController is defined as Restful API entrance for UI.
 *
 * @author jiazhong
 */
@Controller
@RequestMapping(value = "/models")
public class ModelControllerV2 extends BasicController {
    private static final Logger logger = LoggerFactory.getLogger(ModelControllerV2.class);

    public static final char[] VALID_MODELNAME = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890_".toCharArray();

    @Autowired
    @Qualifier("modelMgmtServiceV2")
    private ModelServiceV2 modelServiceV2;

    @Autowired
    @Qualifier("projectServiceV2")
    private ProjectServiceV2 projectServiceV2;

    @Autowired
    @Qualifier("cacheService")
    private CacheService cacheService;

    @RequestMapping(value = "", method = { RequestMethod.GET }, produces = { "application/vnd.apache.kylin-v2+json" })
    @ResponseBody
    public EnvelopeResponse getModelsPaging(@RequestHeader("Accept-Language") String lang, @RequestParam(value = "modelName", required = false) String modelName, @RequestParam(value = "projectName", required = false) String projectName, @RequestParam(value = "pageOffset", required = false, defaultValue = "0") Integer pageOffset, @RequestParam(value = "pageSize", required = false, defaultValue = "10") Integer pageSize) throws IOException {
        MsgPicker.setMsg(lang);

        HashMap<String, Object> data = new HashMap<String, Object>();
        List<DataModelDesc> models = modelServiceV2.listAllModels(modelName, projectName);

        int offset = pageOffset * pageSize;
        int limit = pageSize;

        if (models.size() <= offset) {
            offset = models.size();
            limit = 0;
        }

        if ((models.size() - offset) < limit) {
            limit = models.size() - offset;
        }

        List<DataModelDescResponse> dataModelDescResponses = new ArrayList<DataModelDescResponse>();
        for (DataModelDesc model : modelServiceV2.getModels(modelName, projectName, limit, offset)) {
            DataModelDescResponse dataModelDescResponse = new DataModelDescResponse(model);

            if (projectName != null)
                dataModelDescResponse.setProject(projectName);
            else
                dataModelDescResponse.setProject(projectServiceV2.getProjectOfModel(model.getName()));

            dataModelDescResponses.add(dataModelDescResponse);
        }
        ModelComparator modelComparator = new ModelComparator();
        Collections.sort(dataModelDescResponses, modelComparator);
        data.put("models", dataModelDescResponses);
        data.put("size", models.size());

        return new EnvelopeResponse(ResponseCode.CODE_SUCCESS, data, "");
    }

    @RequestMapping(value = "", method = { RequestMethod.PUT }, produces = { "application/vnd.apache.kylin-v2+json" })
    @ResponseBody
    public EnvelopeResponse updateModelDescV2(@RequestHeader("Accept-Language") String lang, @RequestBody ModelRequest modelRequest) throws IOException {
        MsgPicker.setMsg(lang);

        DataModelDesc modelDesc = deserializeDataModelDescV2(modelRequest);
        modelServiceV2.validateModelDesc(modelDesc);

        String projectName = (null == modelRequest.getProject()) ? ProjectInstance.DEFAULT_PROJECT_NAME : modelRequest.getProject();

        ResourceStore store = ResourceStore.getStore(KylinConfig.getInstanceFromEnv());
        Checkpoint cp = store.checkpoint();
        try {
            boolean createNew = modelServiceV2.unifyModelDesc(modelDesc, false);
            modelDesc = modelServiceV2.updateModelToResourceStore(modelDesc, projectName, createNew, false);
        } catch (Exception ex) {
            cp.rollback();
            cacheService.wipeAllCache();
            throw ex;
        } finally {
            cp.close();
        }

        String descData = JsonUtil.writeValueAsIndentString(modelDesc);
        GeneralResponse data = new GeneralResponse();
        data.setProperty("uuid", modelDesc.getUuid());
        data.setProperty("modelDescData", descData);

        return new EnvelopeResponse(ResponseCode.CODE_SUCCESS, data, "");
    }

    @RequestMapping(value = "/draft", method = { RequestMethod.PUT }, produces = { "application/vnd.apache.kylin-v2+json" })
    @ResponseBody
    public EnvelopeResponse updateModelDescDraftV2(@RequestHeader("Accept-Language") String lang, @RequestBody ModelRequest modelRequest) throws IOException {
        MsgPicker.setMsg(lang);

        DataModelDesc modelDesc = deserializeDataModelDescV2(modelRequest);
        modelServiceV2.validateModelDesc(modelDesc);

        String projectName = (null == modelRequest.getProject()) ? ProjectInstance.DEFAULT_PROJECT_NAME : modelRequest.getProject();

        ResourceStore store = ResourceStore.getStore(KylinConfig.getInstanceFromEnv());
        Checkpoint cp = store.checkpoint();
        try {
            boolean createNew = modelServiceV2.unifyModelDesc(modelDesc, true);
            modelDesc = modelServiceV2.updateModelToResourceStore(modelDesc, projectName, createNew, true);
        } catch (Exception ex) {
            cp.rollback();
            cacheService.wipeAllCache();
            throw ex;
        } finally {
            cp.close();
        }

        String descData = JsonUtil.writeValueAsIndentString(modelDesc);
        GeneralResponse data = new GeneralResponse();
        data.setProperty("uuid", modelDesc.getUuid());
        data.setProperty("modelDescData", descData);

        return new EnvelopeResponse(ResponseCode.CODE_SUCCESS, data, "");
    }

    @RequestMapping(value = "/{modelName}", method = { RequestMethod.DELETE }, produces = { "application/vnd.apache.kylin-v2+json" })
    @ResponseBody
    public void deleteModelV2(@RequestHeader("Accept-Language") String lang, @PathVariable String modelName) throws IOException {
        MsgPicker.setMsg(lang);
        Message msg = MsgPicker.getMsg();

        DataModelDesc desc = modelServiceV2.getMetadataManager().getDataModelDesc(modelName);
        if (null == desc) {
            throw new BadRequestException(String.format(msg.getMODEL_NOT_FOUND(), modelName));
        }
        modelServiceV2.dropModel(desc);
    }

    @RequestMapping(value = "/{modelName}/clone", method = { RequestMethod.PUT }, produces = { "application/vnd.apache.kylin-v2+json" })
    @ResponseBody
    public EnvelopeResponse cloneModelV2(@RequestHeader("Accept-Language") String lang, @PathVariable String modelName, @RequestBody ModelRequest modelRequest) throws IOException {
        MsgPicker.setMsg(lang);
        Message msg = MsgPicker.getMsg();

        String project = modelRequest.getProject();
        MetadataManager metaManager = MetadataManager.getInstance(KylinConfig.getInstanceFromEnv());
        DataModelDesc modelDesc = metaManager.getDataModelDesc(modelName);
        String newModelName = modelRequest.getModelName();

        if (StringUtils.isEmpty(project)) {
            logger.info("Project name should not be empty.");
            throw new BadRequestException(msg.getEMPTY_PROJECT_NAME());
        }

        if (modelDesc == null || StringUtils.isEmpty(modelName)) {
            throw new BadRequestException(msg.getEMPTY_MODEL_NAME());
        }

        if (StringUtils.isEmpty(newModelName)) {
            logger.info("New model name is empty.");
            throw new BadRequestException(msg.getEMPTY_NEW_MODEL_NAME());
        }
        if (!StringUtils.containsOnly(newModelName, VALID_MODELNAME)) {
            logger.info("Invalid Model name {}, only letters, numbers and underline supported.", newModelName);
            throw new BadRequestException(String.format(msg.getINVALID_MODEL_NAME(), newModelName));
        }

        DataModelDesc newModelDesc = DataModelDesc.getCopyOf(modelDesc);
        newModelDesc.setName(newModelName);

        newModelDesc = modelServiceV2.createModelDesc(project, newModelDesc);

        //reload avoid shallow
        metaManager.reloadDataModelDescAt(DataModelDesc.concatResourcePath(newModelName));

        String descData = JsonUtil.writeValueAsIndentString(newModelDesc);
        GeneralResponse data = new GeneralResponse();
        data.setProperty("uuid", newModelDesc.getUuid());
        data.setProperty("modelDescData", descData);

        return new EnvelopeResponse(ResponseCode.CODE_SUCCESS, data, "");
    }

    private DataModelDesc deserializeDataModelDescV2(ModelRequest modelRequest) throws IOException {
        Message msg = MsgPicker.getMsg();

        DataModelDesc desc = null;
        try {
            logger.debug("Saving MODEL " + modelRequest.getModelDescData());
            desc = JsonUtil.readValue(modelRequest.getModelDescData(), DataModelDesc.class);
        } catch (JsonParseException e) {
            logger.error("The data model definition is not valid.", e);
            throw new BadRequestException(msg.getINVALID_MODEL_DEFINITION());
        } catch (JsonMappingException e) {
            logger.error("The data model definition is not valid.", e);
            throw new BadRequestException(msg.getINVALID_MODEL_DEFINITION());
        }
        return desc;
    }

    @RequestMapping(value = "/checkNameAvailability/{modelName}", method = RequestMethod.GET, produces = { "application/vnd.apache.kylin-v2+json" })
    @ResponseBody
    public EnvelopeResponse checkNameAvailabilityV2(@RequestHeader("Accept-Language") String lang, @PathVariable String modelName) throws IOException {
        MsgPicker.setMsg(lang);

        boolean ret = modelServiceV2.checkNameAvailability(modelName);
        return new EnvelopeResponse(ResponseCode.CODE_SUCCESS, ret, "");
    }

    @RequestMapping(value = "/{modelName}/usedCols", method = RequestMethod.GET, produces = { "application/vnd.apache.kylin-v2+json" })
    @ResponseBody
    public EnvelopeResponse getUsedColsV2(@RequestHeader("Accept-Language") String lang, @PathVariable String modelName) {
        MsgPicker.setMsg(lang);

        Map<String, Set<String>> data = new HashMap<>();

        for (Map.Entry<TblColRef, Set<CubeInstance>> entry : modelServiceV2.getUsedDimCols(modelName).entrySet()) {
            populateUsedColResponse(entry.getKey(), entry.getValue(), data);
        }

        for (Map.Entry<TblColRef, Set<CubeInstance>> entry : modelServiceV2.getUsedNonDimCols(modelName).entrySet()) {
            populateUsedColResponse(entry.getKey(), entry.getValue(), data);
        }

        return new EnvelopeResponse(ResponseCode.CODE_SUCCESS, data, "");
    }

    private void populateUsedColResponse(TblColRef tblColRef, Set<CubeInstance> cubeInstances, Map<String, Set<String>> ret) {
        String columnIdentity = tblColRef.getIdentity();
        if (!ret.containsKey(columnIdentity)) {
            ret.put(columnIdentity, Sets.<String> newHashSet());
        }

        for (CubeInstance cubeInstance : cubeInstances) {
            ret.get(columnIdentity).add(cubeInstance.getCanonicalName());
        }
    }

    public void setModelService(ModelServiceV2 modelService) {
        this.modelServiceV2 = modelService;
    }

}

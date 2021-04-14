/*
 * Copyright 2019 WeBank
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.wedatasphere.linkis.datasourcemanager.core.dao;

import com.webank.wedatasphere.linkis.datasourcemanager.common.domain.DatasourceVersion;
import org.apache.ibatis.annotations.Param;


public interface DataSourceVersionDao {

    /**
     * get latest version from datasource id
     * @param dataSourceId
     * @return
     */
    Long getLatestVersion(Long dataSourceId);

    /**
     * insert a version of the datasource
     * @param datasourceVersion
     */
    void insertOne(DatasourceVersion datasourceVersion);

    /**
     * get a version of datasource
     * @param dataSourceId
     * @param version
     * @return
     */
    String selectOneVersion(@Param("dataSourceId")Long dataSourceId, @Param("version")Long version);
}

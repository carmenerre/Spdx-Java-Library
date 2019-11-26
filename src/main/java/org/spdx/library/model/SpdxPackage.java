/**
 * Copyright (c) 2019 Source Auditor Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.spdx.library.model;

import java.util.ArrayList;
import java.util.List;

import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.SpdxConstants;
import org.spdx.storage.IModelStore;

/**
 * @author gary
 *
 */
public class SpdxPackage extends SpdxElement {

	/**
	 * @throws InvalidSPDXAnalysisException
	 */
	public SpdxPackage() throws InvalidSPDXAnalysisException {
		super();
	}

	/**
	 * @param modelStore
	 * @param documentUri
	 * @param id
	 * @param create
	 * @throws InvalidSPDXAnalysisException
	 */
	public SpdxPackage(IModelStore modelStore, String documentUri, String id, boolean create)
			throws InvalidSPDXAnalysisException {
		super(modelStore, documentUri, id, create);
	}

	/**
	 * @param id
	 * @throws InvalidSPDXAnalysisException
	 */
	public SpdxPackage(String id) throws InvalidSPDXAnalysisException {
		super(id);
	}

	@Override
	public String getType() {
		return SpdxConstants.CLASS_SPDX_PACKAGE;
	}

	@Override
	public List<String> verify() {
		// TODO Auto-generated method stub
		return new ArrayList<>();
	}



}

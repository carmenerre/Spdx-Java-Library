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
package org.spdx.storage.listedlicense;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.SpdxConstants;
import org.spdx.library.model.DuplicateSpdxIdException;
import org.spdx.library.model.SpdxIdNotFoundException;
import org.spdx.library.model.license.SpdxListedLicenseException;

import com.google.gson.Gson;

/**
 * Read-only model store for the SPDX listed licenses
 * 
 * @author Gary O'Neall
 *
 */
public abstract class SpdxListedLicenseModelStore implements IListedLicenseStore {
	
	static final Logger logger = LoggerFactory.getLogger(SpdxListedLicenseModelStore.class.getName());
	static final String DEFAULT_LICENSE_LIST_VERSION = "3.7";
	static final String LICENSE_TOC_FILENAME = "licenses.json";
	static final String EXCEPTION_TOC_FILENAME = "exceptions.json";
	static final String JSON_SUFFIX = ".json";
	
	Set<String> licenseIds = new HashSet<>();
	Set<String> exceptionIds = new HashSet<>();
	Map<String, LicenseJson> listedLicenseCache = null;
	Map<String, ExceptionJson> listedExceptionCache = null;
	String licenseListVersion = DEFAULT_LICENSE_LIST_VERSION;
	private int nextId=0;
	private final ReadWriteLock listedLicenseModificationLock = new ReentrantReadWriteLock();

	Gson gson = new Gson();	// we should be able to reuse since all access is within write locks

	
	public SpdxListedLicenseModelStore() throws InvalidSPDXAnalysisException {
		loadIds();
	}
	
	/**
	 * @return InputStream for the Table of Contents of the licenses formated in JSON SPDX
	 * @throws IOException
	 */
	abstract InputStream getTocInputStream() throws IOException;
	
	/**
	 * @return InputStream for the Table of Contents of the exceptions formated in JSON SPDX
	 * @throws IOException
	 */
	abstract InputStream getExceptionTocInputStream() throws IOException;
	
	/**
	 * @return InputStream for a license formated in SPDX JSON
	 * @throws IOException
	 */
	abstract InputStream getLicenseInputStream(String licenseId) throws IOException;
	
	/**
	 * @return InputStream for an exception formated in SPDX JSON
	 * @throws IOException
	 */
	abstract InputStream getExceptionInputStream(String exceptionId) throws IOException;

	/**
	 * Loads all license and exception ID's from the appropriate JSON files
	 * @throws InvalidSPDXAnalysisException
	 */
	private void loadIds() throws InvalidSPDXAnalysisException {
        listedLicenseModificationLock.writeLock().lock();
        try {
            listedLicenseCache = new HashMap<>(); // clear the cache
            listedExceptionCache = new HashMap<>();
            licenseIds = new HashSet<>(); //Clear the listed license IDs to avoid stale licenses.
             //NOTE: This includes deprecated licenses - should this be changed to only return non-deprecated licenses?
            InputStream tocStream = null;
            BufferedReader reader = null;
            try {
            	// read the license IDs
            	tocStream = getTocInputStream();
                reader = new BufferedReader(new InputStreamReader(tocStream));
                StringBuilder tocJsonStr = new StringBuilder();
                String line;
                while((line = reader.readLine()) != null) {
                	tocJsonStr.append(line);
                }
                LicenseJsonTOC jsonToc = gson.fromJson(tocJsonStr.toString(), LicenseJsonTOC.class);
                licenseIds = jsonToc.getLicenseIds();
                this.licenseListVersion = jsonToc.getLicenseListVersion();
                
                // read the exception ID's
                tocStream = getExceptionTocInputStream();
                reader = new BufferedReader(new InputStreamReader(tocStream));
                tocJsonStr  = new StringBuilder();
                while((line = reader.readLine()) != null) {
                	tocJsonStr.append(line);
                }
                ExceptionJsonTOC exceptionToc = gson.fromJson(tocJsonStr.toString(), ExceptionJsonTOC.class);
                exceptionIds = exceptionToc.getExceptionIds();
            } catch (MalformedURLException e) {
				logger.error("Json TOC URL invalid, using local TOC file");
				throw(new SpdxListedLicenseException("License TOC URL invalid"));
			} catch (IOException e) {
				logger.error("I/O error opening Json TOC URL");
				throw(new SpdxListedLicenseException("I/O error reading license TOC"));
			} finally {
            	if (reader != null) {
            		try {
						reader.close();
					} catch (IOException e) {
						logger.warn("Unable to close JSON TOC reader");
					}
            	} else if (tocStream != null) {
            		try {
						tocStream.close();
					} catch (IOException e) {
						logger.warn("Unable to close JSON TOC input stream");
					}
            	}
            }
        } finally {
            listedLicenseModificationLock.writeLock().unlock();
        }
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#exists(java.lang.String, java.lang.String)
	 */
	@Override
	public boolean exists(String documentUri, String id) {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			return false;
		}
		return (this.licenseIds.contains(id) || this.exceptionIds.contains(id));
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#create(java.lang.String, java.lang.String, java.lang.String)
	 */
	@Override
	public void create(String documentUri, String id, String type) throws InvalidSPDXAnalysisException {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			logger.error("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
			throw new SpdxIdNotFoundException("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
		}
		listedLicenseModificationLock.writeLock().lock();
		try {
			if (this.licenseIds.contains(id) || this.exceptionIds.contains(id)) {
				logger.error("Duplicate SPDX ID on create: "+id);;
				throw new DuplicateSpdxIdException("ID "+id+" already exists.");
			}
			if (SpdxConstants.CLASS_SPDX_LISTED_LICENSE.equals(type)) {
				this.licenseIds.add(id);
				this.listedLicenseCache.put(id, new LicenseJson(id));
			} else if (SpdxConstants.CLASS_SPDX_LICENSE_EXCEPTION.equals(type)) {
				this.exceptionIds.add(id);
				this.listedExceptionCache.put(id,  new ExceptionJson(id));
			}
		} finally {
			listedLicenseModificationLock.writeLock().unlock();
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#getPropertyValueNames(java.lang.String, java.lang.String)
	 */
	@Override
	public List<String> getPropertyValueNames(String documentUri, String id) throws InvalidSPDXAnalysisException  {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			logger.error("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
			throw new SpdxIdNotFoundException("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
		}
		boolean isLicenseId = false;
		boolean isExceptionId = false;
		listedLicenseModificationLock.readLock().lock();
		try {
			if (licenseIds.contains(id)) {
				isLicenseId = true;
			} else if (exceptionIds.contains(id)) {
				isExceptionId = true;
			}
		} finally {
			listedLicenseModificationLock.readLock().unlock();
		}
		if (isLicenseId) {
			LicenseJson license = fetchLicenseJson(id);
			return license.getPropertyValueNames();
		} else if (isExceptionId) {
			ExceptionJson exc = fetchExceptionJson(id);
			return exc.getPropertyValueListNames();
		} else {
			logger.error("ID "+id+" is not a listed license ID nor a listed exception ID");
			throw new SpdxIdNotFoundException("ID "+id+" is not a listed license ID nor a listed exception ID");
		}
	}

	/**
	 * @param id License ID
	 * @return License JSON for the ID - reading from the input stream if needed
	 * @throws InvalidSPDXAnalysisException
	 */
	private LicenseJson fetchLicenseJson(String id) throws InvalidSPDXAnalysisException {
		listedLicenseModificationLock.readLock().lock();
		try {
			if (!this.licenseIds.contains(id)) {
				logger.error("Attemting to get property values on non-existent ID "+id);
				throw new SpdxIdNotFoundException("ID "+id+" not found.");
			}
			if (this.listedLicenseCache.containsKey(id)) {
				return this.listedLicenseCache.get(id);
			}
		} finally {
			listedLicenseModificationLock.readLock().unlock();
		}
		// If we got here, it wasn't in the cache
		listedLicenseModificationLock.writeLock().lock();
		try {
			// have to retest since we were unlocked
			if (!this.licenseIds.contains(id)) {
				logger.error("Attemting to get property values on non-existent ID "+id);
				throw new SpdxIdNotFoundException("ID "+id+" not found.");
			}
			if (!this.listedLicenseCache.containsKey(id)) {
	            InputStream jsonStream = null;
	            BufferedReader reader = null;
	            try {
	            	jsonStream = getLicenseInputStream(id);
	                reader = new BufferedReader(new InputStreamReader(jsonStream, "UTF-8"));
	                StringBuilder licenseJsonStr = new StringBuilder();
	                String line;
	                while((line = reader.readLine()) != null) {
	                	licenseJsonStr.append(line);
	                }
	                LicenseJson license = gson.fromJson(licenseJsonStr.toString(), LicenseJson.class);
	                this.listedLicenseCache.put(id, license);
	            } catch (MalformedURLException e) {
					logger.error("Json license invalid for ID "+id);
					throw(new SpdxListedLicenseException("JSON license URL invalid for ID "+id));
				} catch (IOException e) {
					logger.error("I/O error opening Json license URL");
					throw(new SpdxListedLicenseException("I/O Error reading license data for ID "+id));
				} finally {
	            	if (reader != null) {
	            		try {
							reader.close();
						} catch (IOException e) {
							logger.warn("Unable to close JSON TOC reader");
						}
	            	} else if (jsonStream != null) {
	            		try {
							jsonStream.close();
						} catch (IOException e) {
							logger.warn("Unable to close JSON TOC input stream");
						}
	            	}
				}
			}
			return this.listedLicenseCache.get(id);
		} finally {
			listedLicenseModificationLock.writeLock().unlock();
		}
	}
	
	/**
	 * @param id License ID
	 * @return Exception JSON for the ID - reading from the input stream if needed
	 * @throws InvalidSPDXAnalysisException
	 */
	private ExceptionJson fetchExceptionJson(String id) throws InvalidSPDXAnalysisException {
		listedLicenseModificationLock.readLock().lock();
		try {
			if (!this.exceptionIds.contains(id)) {
				logger.error("Attemting to get property values on non-existent ID "+id);
				throw new SpdxIdNotFoundException("ID "+id+" not found.");
			}
			if (this.listedExceptionCache.containsKey(id)) {
				return this.listedExceptionCache.get(id);
			}
		} finally {
			listedLicenseModificationLock.readLock().unlock();
		}
		// If we got here, it wasn't in the cache
		listedLicenseModificationLock.writeLock().lock();
		try {
			// have to retest since we were unlocked
			if (!this.exceptionIds.contains(id)) {
				logger.error("Attemting to get property values on non-existent ID "+id);
				throw new SpdxIdNotFoundException("ID "+id+" not found.");
			}
			if (!this.listedExceptionCache.containsKey(id)) {
	            InputStream jsonStream = null;
	            BufferedReader reader = null;
	            try {
	            	jsonStream = getExceptionInputStream(id);
	                reader = new BufferedReader(new InputStreamReader(jsonStream, "UTF-8"));
	                StringBuilder exceptionJsonStr = new StringBuilder();
	                String line;
	                while((line = reader.readLine()) != null) {
	                	exceptionJsonStr.append(line);
	                }
	                ExceptionJson exc = gson.fromJson(exceptionJsonStr.toString(), ExceptionJson.class);
	                this.listedExceptionCache.put(id, exc);
	            } catch (MalformedURLException e) {
					logger.error("Json license invalid for ID "+id);
					throw(new SpdxListedLicenseException("JSON license URL invalid for ID "+id));
				} catch (IOException e) {
					logger.error("I/O error opening Json license URL");
					throw(new SpdxListedLicenseException("I/O Error reading license data for ID "+id));
				} finally {
	            	if (reader != null) {
	            		try {
							reader.close();
						} catch (IOException e) {
							logger.warn("Unable to close JSON TOC reader");
						}
	            	} else if (jsonStream != null) {
	            		try {
							jsonStream.close();
						} catch (IOException e) {
							logger.warn("Unable to close JSON TOC input stream");
						}
	            	}
				}
			}
			return this.listedExceptionCache.get(id);
		} finally {
			listedLicenseModificationLock.writeLock().unlock();
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#getPropertyValueListNames(java.lang.String, java.lang.String)
	 */
	@Override
	public List<String> getPropertyValueListNames(String documentUri, String id) throws InvalidSPDXAnalysisException  {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			logger.error("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
			throw new SpdxIdNotFoundException("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
		}
		boolean isLicenseId = false;
		boolean isExceptionId = false;
		listedLicenseModificationLock.readLock().lock();
		try {
			if (licenseIds.contains(id)) {
				isLicenseId = true;
			} else if (exceptionIds.contains(id)) {
				isExceptionId = true;
			}
		} finally {
			listedLicenseModificationLock.readLock().unlock();
		}
		if (isLicenseId) {
			LicenseJson license = fetchLicenseJson(id);
			return license.getPropertyValueListNames();
		} else if (isExceptionId) {
			ExceptionJson exc = fetchExceptionJson(id);
			return exc.getPropertyValueListNames();
		} else {
			logger.error("ID "+id+" is not a listed license ID nor a listed exception ID");
			throw new SpdxIdNotFoundException("ID "+id+" is not a listed license ID nor a listed exception ID");
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#setTypedValue(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)
	 */
	@Override
	public void setTypedValue(String documentUri, String id, String propertyName, String valueId, String type)  throws InvalidSPDXAnalysisException  {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			logger.error("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
			throw new SpdxIdNotFoundException("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
		}
		boolean isLicenseId = false;
		boolean isExceptionId = false;
		listedLicenseModificationLock.readLock().lock();
		try {
			if (licenseIds.contains(id)) {
				isLicenseId = true;
			} else if (exceptionIds.contains(id)) {
				isExceptionId = true;
			}
		} finally {
			listedLicenseModificationLock.readLock().unlock();
		}
		if (isLicenseId) {
			LicenseJson license = fetchLicenseJson(id);
			license.setTypedProperty(propertyName, valueId, type);
		} else if (isExceptionId) {
			ExceptionJson exc = fetchExceptionJson(id);
			exc.setTypedProperty(propertyName, valueId, type);
		} else {
			logger.error("ID "+id+" is not a listed license ID nor a listed exception ID");
			throw new SpdxIdNotFoundException("ID "+id+" is not a listed license ID nor a listed exception ID");
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#setPrimitiveValue(java.lang.String, java.lang.String, java.lang.String, java.lang.Object)
	 */
	@Override
	public void setPrimitiveValue(String documentUri, String id, String propertyName, Object value)  throws InvalidSPDXAnalysisException  {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			logger.error("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
			throw new SpdxIdNotFoundException("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
		}
		boolean isLicenseId = false;
		boolean isExceptionId = false;
		listedLicenseModificationLock.readLock().lock();
		try {
			if (licenseIds.contains(id)) {
				isLicenseId = true;
			} else if (exceptionIds.contains(id)) {
				isExceptionId = true;
			}
		} finally {
			listedLicenseModificationLock.readLock().unlock();
		}
		if (isLicenseId) {
			LicenseJson license = fetchLicenseJson(id);
			license.setPrimativeValue(propertyName, value);
		} else if (isExceptionId) {
			ExceptionJson exc = fetchExceptionJson(id);
			exc.setPrimativeValue(propertyName, value);
		} else {
			logger.error("ID "+id+" is not a listed license ID nor a listed exception ID");
			throw new SpdxIdNotFoundException("ID "+id+" is not a listed license ID nor a listed exception ID");
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#clearPropertyValueList(java.lang.String, java.lang.String, java.lang.String)
	 */
	@Override
	public void clearPropertyValueList(String documentUri, String id, String propertyName)  throws InvalidSPDXAnalysisException  {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			logger.error("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
			throw new SpdxIdNotFoundException("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
		}
		boolean isLicenseId = false;
		boolean isExceptionId = false;
		listedLicenseModificationLock.readLock().lock();
		try {
			if (licenseIds.contains(id)) {
				isLicenseId = true;
			} else if (exceptionIds.contains(id)) {
				isExceptionId = true;
			}
		} finally {
			listedLicenseModificationLock.readLock().unlock();
		}
		if (isLicenseId) {
			LicenseJson license = fetchLicenseJson(id);
			license.clearPropertyValueList(propertyName);
		} else if (isExceptionId) {
			ExceptionJson exc = fetchExceptionJson(id);
			exc.clearPropertyValueList(propertyName);
		} else {
			logger.error("ID "+id+" is not a listed license ID nor a listed exception ID");
			throw new SpdxIdNotFoundException("ID "+id+" is not a listed license ID nor a listed exception ID");
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#addTypedValueToList(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)
	 */
	@Override
	public void addTypedValueToList(String documentUri, String id, String propertyName, String valueId, String type)  throws InvalidSPDXAnalysisException  {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			logger.error("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
			throw new SpdxIdNotFoundException("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
		}
		boolean isLicenseId = false;
		boolean isExceptionId = false;
		listedLicenseModificationLock.readLock().lock();
		try {
			if (licenseIds.contains(id)) {
				isLicenseId = true;
			} else if (exceptionIds.contains(id)) {
				isExceptionId = true;
			}
		} finally {
			listedLicenseModificationLock.readLock().unlock();
		}
		if (isLicenseId) {
			LicenseJson license = fetchLicenseJson(id);
			license.addValueToList(propertyName, valueId, type);
		} else if (isExceptionId) {
			ExceptionJson exc = fetchExceptionJson(id);
			exc.addValueToList(propertyName, valueId, type);
		} else {
			logger.error("ID "+id+" is not a listed license ID nor a listed exception ID");
			throw new SpdxIdNotFoundException("ID "+id+" is not a listed license ID nor a listed exception ID");
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#addPrimitiveValueToList(java.lang.String, java.lang.String, java.lang.String, java.lang.Object)
	 */
	@Override
	public void addPrimitiveValueToList(String documentUri, String id, String propertyName, Object value)  throws InvalidSPDXAnalysisException  {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			logger.error("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
			throw new SpdxIdNotFoundException("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
		}
		boolean isLicenseId = false;
		boolean isExceptionId = false;
		listedLicenseModificationLock.readLock().lock();
		try {
			if (licenseIds.contains(id)) {
				isLicenseId = true;
			} else if (exceptionIds.contains(id)) {
				isExceptionId = true;
			}
		} finally {
			listedLicenseModificationLock.readLock().unlock();
		}
		if (isLicenseId) {
			LicenseJson license = fetchLicenseJson(id);
			license.addPrimitiveValueToList(propertyName, value);
		} else if (isExceptionId) {
			ExceptionJson exc = fetchExceptionJson(id);
			exc.addPrimitiveValueToList(propertyName, value);
		} else {
			logger.error("ID "+id+" is not a listed license ID nor a listed exception ID");
			throw new SpdxIdNotFoundException("ID "+id+" is not a listed license ID nor a listed exception ID");
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#getValueList(java.lang.String, java.lang.String, java.lang.String)
	 */
	@Override
	public List<?> getValueList(String documentUri, String id, String propertyName)  throws InvalidSPDXAnalysisException  {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			logger.error("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
			throw new SpdxIdNotFoundException("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
		}
		boolean isLicenseId = false;
		boolean isExceptionId = false;
		listedLicenseModificationLock.readLock().lock();
		try {
			if (licenseIds.contains(id)) {
				isLicenseId = true;
			} else if (exceptionIds.contains(id)) {
				isExceptionId = true;
			}
		} finally {
			listedLicenseModificationLock.readLock().unlock();
		}
		if (isLicenseId) {
			LicenseJson license = fetchLicenseJson(id);
			return license.getValueList(propertyName);
		} else if (isExceptionId) {
			ExceptionJson exc = fetchExceptionJson(id);
			return exc.getValueList(propertyName);
		} else {
			logger.error("ID "+id+" is not a listed license ID nor a listed exception ID");
			throw new SpdxIdNotFoundException("ID "+id+" is not a listed license ID nor a listed exception ID");
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#getValue(java.lang.String, java.lang.String, java.lang.String)
	 */
	@Override
	public Object getValue(String documentUri, String id, String propertyName)  throws InvalidSPDXAnalysisException  {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			logger.error("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
			throw new SpdxIdNotFoundException("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
		}
		boolean isLicenseId = false;
		boolean isExceptionId = false;
		listedLicenseModificationLock.readLock().lock();
		try {
			if (licenseIds.contains(id)) {
				isLicenseId = true;
			} else if (exceptionIds.contains(id)) {
				isExceptionId = true;
			}
		} finally {
			listedLicenseModificationLock.readLock().unlock();
		}
		if (isLicenseId) {
			LicenseJson license = fetchLicenseJson(id);
			return license.getValue(propertyName);
		} else if (isExceptionId) {
			ExceptionJson exc = fetchExceptionJson(id);
			return exc.getValue(propertyName);
		} else {
			logger.error("ID "+id+" is not a listed license ID nor a listed exception ID");
			throw new SpdxIdNotFoundException("ID "+id+" is not a listed license ID nor a listed exception ID");
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.IModelStore#getNextId(org.spdx.storage.IModelStore.IdType, java.lang.String)
	 */
	@Override
	public String getNextId(IdType idType, String documentUri)  throws InvalidSPDXAnalysisException  {
		if (!SpdxConstants.LISTED_LICENSE_DOCUMENT_URI.equals(documentUri)) {
			logger.error("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
			throw new SpdxIdNotFoundException("Document URI for SPDX listed licenses is expected to be "+
					SpdxConstants.LISTED_LICENSE_DOCUMENT_URI + ".  Supplied document URI was "+documentUri);
		}
		this.listedLicenseModificationLock.writeLock().lock();
		try {
			return "SpdxLicenseGeneratedId-"+String.valueOf(this.nextId++);
		} finally {
			this.listedLicenseModificationLock.writeLock().unlock();
		}
	}

	@Override
	public List<String> getSpdxListedLicenseIds() {
		this.listedLicenseModificationLock.readLock().lock();
		try {
			List<String> retval = new ArrayList<>();
			retval.addAll(this.licenseIds);
			return retval;
		} finally {
			this.listedLicenseModificationLock.readLock().unlock();
		}
	}

	@Override
	public String getLicenseListVersion() {
		this.listedLicenseModificationLock.readLock().lock();
		try {
			return this.licenseListVersion;
		} finally {
			this.listedLicenseModificationLock.readLock().unlock();
		}
	}
	
	public List<String> getSpdxListedExceptionIds() {
		this.listedLicenseModificationLock.readLock().lock();
		try {
			List<String> retval = new ArrayList<>();
			retval.addAll(this.exceptionIds);
			return retval;
		} finally {
			this.listedLicenseModificationLock.readLock().unlock();
		}
	}
	
	/**
	 * @param listedLicenseDocumentUri
	 * @param licenseId
	 * @return true if the licenseId belongs to an SPDX listed license
	 */
	public boolean isSpdxListedLicenseId(String listedLicenseDocumentUri, String licenseId) {
		this.listedLicenseModificationLock.readLock().lock();
		try {
			return this.licenseIds.contains(licenseId);
		} finally {
			this.listedLicenseModificationLock.readLock().unlock();
		}
	}
	
	/**
	 * @param listedLicenseDocumentUri
	 * @param exceptionId
	 * @return true if the exceptionId belongs to an SPDX listed exception
	 */
	public boolean isSpdxListedExceptionId(String listedLicenseDocumentUri, String exceptionId) {
		this.listedLicenseModificationLock.readLock().lock();
		try {
			return this.exceptionIds.contains(exceptionId);
		} finally {
			this.listedLicenseModificationLock.readLock().unlock();
		}
	}

}

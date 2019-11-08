/*
 * JasperReports - Free Java Reporting Library.
 * Copyright (C) 2001 - 2019 TIBCO Software Inc. All rights reserved.
 * http://www.jaspersoft.com
 *
 * Unless you have purchased a commercial license agreement from Jaspersoft,
 * the following license terms apply:
 *
 * This program is part of JasperReports.
 *
 * JasperReports is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JasperReports is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with JasperReports. If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.jasperreports.chrome;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.collections4.map.AbstractReferenceMap.ReferenceStrength;
import org.apache.commons.collections4.map.ReferenceIdentityMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.JRRuntimeException;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import net.sf.jasperreports.engine.util.ConcurrentMapping;
import net.sf.jasperreports.repo.RepositoryUtil;

/**
 * @author Lucian Chirita (lucianc@users.sourceforge.net)
 */
public class ResourceManager
{
	private static final Log log = LogFactory.getLog(ResourceManager.class);
	
	private static final String TEMP_FILE_PREFIX = "jr_res_";
	private static final int COPY_BUFFER_SIZE = 0x4000;
	
	private static final ResourceManager INSTANCE = new ResourceManager();
	
	public static ResourceManager instance()
	{
		return INSTANCE;
	}
	
	private final Map<JasperReportsContext, ContextMappings> contextMappings;
	
	public ResourceManager()
	{
		//TODO schedule a repeating job to purge contexts
		this.contextMappings = new ReferenceIdentityMap<>(ReferenceStrength.WEAK, ReferenceStrength.HARD);
	}
	
	//TODO use java.nio?
	public File getTempFolder(JasperReportsContext jasperReportsContext)
	{
		ContextMappings mappings = contextMappings(jasperReportsContext);
		return mappings.tempFolder;
	}

	protected ContextMappings contextMappings(JasperReportsContext jasperReportsContext)
	{
		ContextMappings mappings;
		synchronized (contextMappings)
		{
			//TODO lucianc use safer keys (classloader)
			mappings = contextMappings.get(jasperReportsContext);
			if (mappings == null)
			{
				mappings = new ContextMappings(jasperReportsContext);
				contextMappings.put(jasperReportsContext, mappings);
			}
		}
		return mappings;
	}

	public String getResourceLocation(String resourceLocation, JasperReportsContext jasperReportsContext)
	{
		File resourceMapping = resourceMapping(resourceLocation, jasperReportsContext);
		if (resourceMapping == null)
		{
			throw new JRRuntimeException(RepositoryUtil.EXCEPTION_MESSAGE_KEY_INPUT_STREAM_NOT_FOUND,
				new Object[]{resourceLocation});
		}
		return resourceMapping.toURI().toString();
	}

	public String copyDataResource(String resourceName, JasperReportsContext jasperReportsContext,
			InputStream data)
	{
		ContextMappings mappings = contextMappings(jasperReportsContext);
		File resourceFile = mappings.dataResourceMapping(resourceName, data, jasperReportsContext);
		return resourceFile.toURI().toString();
	}

	public String getDataResourceLocation(String resourceName, JasperReportsContext jasperReportsContext)
	{
		ContextMappings mappings = contextMappings(jasperReportsContext);
		File resourceFile = mappings.dataResourceMapping(resourceName, jasperReportsContext);
		if (resourceFile == null)
		{
			throw new JRRuntimeException(RepositoryUtil.EXCEPTION_MESSAGE_KEY_INPUT_STREAM_NOT_FOUND,
					new Object[]{resourceName});
		}
		return resourceFile.toURI().toString();
	}
	
	protected File resourceMapping(String resourceLocation, JasperReportsContext jasperReportsContext)
	{
		if (jasperReportsContext instanceof SimpleJasperReportsContext)
		{
			JasperReportsContext parentContext = ((SimpleJasperReportsContext) jasperReportsContext).getParent();
			if (parentContext != null)
			{
				//looking first in the parent so that contexts that share a parent would also share the mappings
				File parentMapping = resourceMapping(resourceLocation, parentContext);
				if (parentMapping != null)
				{
					return parentMapping;
				}
			}
		}
		
		ContextMappings mappings = contextMappings(jasperReportsContext);		
		File resourceFile = mappings.resourceMapping(resourceLocation, jasperReportsContext);
		return resourceFile;
	}

	protected File copyResource(String resourceLocation, JasperReportsContext jasperReportsContext, File tempFolder)
	{
		try(InputStream input = RepositoryUtil.getInstance(jasperReportsContext).getInputStreamFromLocation(resourceLocation))
		{
			File file = createTempFile(resourceLocation, tempFolder);
			copyToFile(resourceLocation, input, file);
			return file;
		}
		catch (JRException e)
		{
			if (RepositoryUtil.EXCEPTION_MESSAGE_KEY_INPUT_STREAM_NOT_FOUND.equals(e.getMessageKey()))
			{
				return null;
			}
			
			throw new JRRuntimeException(e);
		}
		catch (IOException e)
		{
			throw new JRRuntimeException(e);
		}
	}

	protected File createTempFile(String resourceLocation, File tempFolder)
	{
		try
		{
			String resourceName = getResourceName(resourceLocation);
			File file = File.createTempFile(TEMP_FILE_PREFIX, "_" + resourceName, tempFolder);
			file.deleteOnExit();//TODO lucianc leak
			return file;
		}
		catch (IOException e)
		{
			throw new JRRuntimeException(e);
		}
	}	

	protected void copyToFile(String resourceLocation, InputStream data, File file)
	{
		try
		{
			if (log.isDebugEnabled())
			{
				log.debug("copying " + resourceLocation + " to " + file);
			}

			byte[] buf = new byte[COPY_BUFFER_SIZE];
			try (OutputStream output = new FileOutputStream(file))
			{
				int read = 0;
				while ((read = data.read(buf)) > 0)
				{
					output.write(buf, 0, read);
				}
			}
		}
		catch (IOException e)
		{
			throw new JRRuntimeException(e);
		}
	}
	
	protected String getResourceName(String resourceLocation)
	{
		// location can be both classpath resource and file path
		int slashIndex = resourceLocation.lastIndexOf('/');
		int separatorIndex = resourceLocation.lastIndexOf(File.separator);
		int nameIndex = Math.max(slashIndex, separatorIndex);
		return nameIndex >= 0 ? resourceLocation.substring(nameIndex + 1) : resourceLocation;
	}
	
	protected class ContextMappings
	{
		private final ConcurrentMapping<String, File> resourceFiles;
		private final ConcurrentMapping<String, File> dataResourceFiles;
		protected final File tempFolder;
		
		public ContextMappings(JasperReportsContext jasperReportsContext)
		{
			String tempPath = JRPropertiesUtil.getInstance(jasperReportsContext).getProperty(Chrome.PROPERTY_TEMPDIR_PATH);
			if (tempPath == null)
			{
				tempPath = System.getProperty("java.io.tmpdir");
			}
			
			this.tempFolder = new File(tempPath);
			if (this.tempFolder.exists() && this.tempFolder.isDirectory())
			{
				log.info("Resources temp folder is " + tempPath);
			}
			else
			{
				log.error("The resources temp folder " + tempPath + " does not exist.");
			}
			
			this.resourceFiles = new ConcurrentMapping<>((key, context) 
					-> copyResource(key, context, tempFolder));
			
			this.dataResourceFiles = new ConcurrentMapping<>((resourceName, context)
					-> createTempFile(resourceName, tempFolder));
		}
		
		public File resourceMapping(String resourceLocation, JasperReportsContext jasperReportsContext)
		{
			return resourceFiles.get(resourceLocation, jasperReportsContext);
		}
		
		public File dataResourceMapping(String resourceName, InputStream data, JasperReportsContext jasperReportsContext)
		{
			File resourceFile = dataResourceFiles.get(resourceName, jasperReportsContext);
			synchronized (resourceFile)
			{
				if (resourceFile.length() == 0)
				{
					copyToFile(resourceName, data, resourceFile);
				}
				else
				{
					if (log.isDebugEnabled())
					{
						log.debug("file " + resourceFile + " already written for " + resourceName);
					}
				}
			}
			return resourceFile;
		}
		
		public File dataResourceMapping(String resourceName, JasperReportsContext jasperReportsContext)
		{
			return dataResourceFiles.get(resourceName, jasperReportsContext);
		}
		
		@Override
		protected void finalize()
		{
			for (Iterator<File> fileIt = resourceFiles.currentValues(); fileIt.hasNext();)
			{
				File file = fileIt.next();
				boolean deleted = file.delete();
				if (log.isDebugEnabled())
				{
					log.debug("deleted " + file + ": " + deleted);
				}
			}
			
			resourceFiles.clear();
			
			for (Iterator<File> fileIt = dataResourceFiles.currentValues(); fileIt.hasNext();)
			{
				File file = fileIt.next();
				boolean deleted = file.delete();
				if (log.isDebugEnabled())
				{
					log.debug("deleted " + file + ": " + deleted);
				}
			}
			dataResourceFiles.clear();
		}
	}
	
}
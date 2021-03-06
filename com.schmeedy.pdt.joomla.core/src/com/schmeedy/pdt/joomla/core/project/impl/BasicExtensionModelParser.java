package com.schmeedy.pdt.joomla.core.project.impl;

import java.io.InputStream;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

import com.schmeedy.pdt.joomla.core.project.model.BasicExtensionModel;
import com.schmeedy.pdt.joomla.core.project.model.ExtensionResource;
import com.schmeedy.pdt.joomla.core.project.model.ExtensionType;
import com.schmeedy.pdt.joomla.core.project.model.JoomlaProjectModelFactory;
import com.schmeedy.pdt.joomla.core.project.model.LanguageResource;
import com.schmeedy.pdt.joomla.core.project.model.ManifestVersion;
import com.schmeedy.pdt.joomla.core.project.model.MediaResource;
import com.schmeedy.pdt.joomla.core.project.model.ResourceType;

public class BasicExtensionModelParser {

	private final JoomlaProjectModelFactory factory = JoomlaProjectModelFactory.eINSTANCE;
	
	public BasicExtensionModel parse(InputStream inputStream, String manifestName) throws ParsingException {
		final XMLEventReader eventReader;
		try {
			eventReader = XMLInputFactory.newInstance().createXMLEventReader(inputStream);
		} catch (final XMLStreamException e) {
			throw new ParsingException("Failed to create STAX reader.", e);
		}
		try {
			return doParse(eventReader, manifestName);
		} catch (final XMLStreamException e) {
			throw new ParsingException("Exception while parsing extension model.", e);
		}
	}
	
	private BasicExtensionModel doParse(XMLEventReader eventReader, String manifestName) throws XMLStreamException {
		final BasicExtensionModel extensionModel = factory.createBasicExtensionModel();
		int elementDepth = 0;
		boolean inAdministration = false;
		ResourceType resourceType = null;
		String resourceFolder = null;
		String mediaDestination = null;
		String symbolicName = null;
		while (eventReader.hasNext()) {
			final XMLEvent event = eventReader.nextEvent();
			switch (event.getEventType()) {
				case XMLStreamConstants.START_ELEMENT:
					elementDepth++;
					final StartElement startElement = event.asStartElement();
					final String startElementName = startElement.getName().getLocalPart();
					if (elementDepth == 1) {
						final Attribute versionAtt = startElement.getAttributeByName(new QName("version"));
						if (versionAtt != null) {
							if (versionAtt.getValue().trim().startsWith("1.5")) {
								extensionModel.setManifestVersion(ManifestVersion.ONE_FIVE);
							} else if (versionAtt.getValue().trim().startsWith("1.6")) {
								extensionModel.setManifestVersion(ManifestVersion.ONE_SIX);
							}
						}
						final Attribute typeAtt = startElement.getAttributeByName(new QName("type"));
						if (typeAtt != null) {
							final ExtensionType extensionType = getExtensionTypeForLiteral(typeAtt.getValue());
							if (extensionType != null) {
								extensionModel.setType(extensionType);
							}
						}
						final String group = getAttributeValue(startElement, "group");
						if (group != null) {
							extensionModel.setGroup(group);
						}
					} else if (elementDepth == 2) {
						if ("name".equals(startElementName)) {
							extensionModel.setName(eventReader.getElementText());
							elementDepth--; // eventReader.getElementText() skips end element event
						} else if ("administration".equals(startElementName)) {
							inAdministration = true;
						}
					}
					
					if ("files".equals(startElementName)) {
						resourceType = ResourceType.GENERIC_FILE;
						resourceFolder = getAttributeValue(startElement, "folder");
					} else if ("languages".equals(startElementName)) {
						resourceType = ResourceType.LANGUAGE;
						resourceFolder = getAttributeValue(startElement, "folder");
					} else if ("media".equals(startElementName)) {
						resourceType = ResourceType.MEDIA;
						resourceFolder = getAttributeValue(startElement, "folder");
						mediaDestination = getAttributeValue(startElement, "destination");
					} else if (elementDepth == 2 && ("install".equals(startElementName) || "uninstall".equals(startElementName))) {
						resourceType = "install".equals(startElementName) ? ResourceType.SPECIAL_INSTALL_SQL : ResourceType.SPECIAL_UNINSTALL_SQL;
						resourceFolder = null;
					} else if ("file".equals(startElementName) || "filename".equals(startElementName) || "folder".equals(startElementName) || "language".equals(startElementName)) {
						final String content = eventReader.getElementText().trim();
						elementDepth--; // eventReader.getElementText() skips end element event
						final int installPackagePathSegments = resourceFolder == null ? 0 : new Path(resourceFolder.replace("\\", "/")).segmentCount();
						final String manifestRelativePathPrefix = resourceFolder == null ? "" : resourceFolder.endsWith("/") || resourceFolder.endsWith("\\") ? resourceFolder : resourceFolder + "/";
						final IPath manifestRelativePath = new Path(manifestRelativePathPrefix + content);
						/*
						 * This mimics logic in installation commands for plugin / module - pretty nasty
						 */
						if (symbolicName == null) {
							if (getAttributeValue(startElement, "plugin") != null) {
								symbolicName = getAttributeValue(startElement, "plugin");
							} else if (getAttributeValue(startElement, "module") != null) {
								symbolicName = getAttributeValue(startElement, "module");
							}
						}
						
						final ExtensionResource resource;
						if (resourceType == ResourceType.MEDIA) {
							resource = JoomlaProjectModelFactory.eINSTANCE.createMediaResource();
							((MediaResource) resource).setDestination(mediaDestination);
						} else if (resourceType == ResourceType.LANGUAGE) {
							resource = JoomlaProjectModelFactory.eINSTANCE.createLanguageResource();
							((LanguageResource) resource).setLanguageTag(getAttributeValue(startElement, "tag"));
						} else {
							resource = JoomlaProjectModelFactory.eINSTANCE.createExtensionResource();
						}
						resource.setResourceType(resourceType);
						resource.setFolder("folder".equals(startElementName));
						resource.setInAdministration(inAdministration);
						resource.setManifestRelativePath(manifestRelativePath);
						resource.setInstallPackagePathSegments(installPackagePathSegments);
						extensionModel.getResources().add(resource);
					} else if ("installfile".equals(startElementName) || "uninstallfile".equals(startElementName)) {
						final IPath manifestRelativePath = new Path(eventReader.getElementText().trim());
						final ExtensionResource resource = JoomlaProjectModelFactory.eINSTANCE.createExtensionResource();
						resource.setResourceType("installfile".equals(startElementName) ? ResourceType.SPECIAL_INSTALL_SCRIPT : ResourceType.SPECIAL_UNINSTALL_SCRIPT);
						resource.setManifestRelativePath(manifestRelativePath);
						extensionModel.getResources().add(resource);
					}
					break;
				case XMLStreamConstants.END_ELEMENT:
					final String endElementName = event.asEndElement().getName().getLocalPart();
					if (elementDepth == 2 && "administration".equals(endElementName)) {
						inAdministration = false;
					} else if ("files".equals(endElementName) || "languages".equals(endElementName) || "media".equals(endElementName)) {
						resourceType = null;
						resourceFolder = null;
						mediaDestination = null;
					}
					elementDepth--;
					break;
			}
		}
		extensionModel.setSymbolicName(symbolicName);
		return extensionModel;
	}

	private String getAttributeValue(StartElement startElement, String attName) {
		return startElement.getAttributeByName(new QName(attName)) == null ? null : startElement.getAttributeByName(new QName(attName)).getValue().trim();
	}

	private ExtensionType getExtensionTypeForLiteral(String value) {
		for (final ExtensionType type : ExtensionType.values()) {
			if (type.getLiteral().equals(value)) {
				return type;
			}
		}
		return null;
	}

	public static class ParsingException extends Exception {

		private static final long serialVersionUID = 1L;

		public ParsingException(String message, Throwable cause) {
			super(message, cause);
		}

		public ParsingException(String message) {
			super(message);
		}

		public ParsingException(Throwable cause) {
			super(cause);
		}
		
	}
	
}

/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.boot.internal;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.boot.MappingException;
import org.hibernate.boot.archive.internal.UrlInputStreamAccess;
import org.hibernate.boot.archive.scan.internal.ClassDescriptorImpl;
import org.hibernate.boot.archive.scan.internal.MappingFileDescriptorImpl;
import org.hibernate.boot.archive.scan.internal.PackageDescriptorImpl;
import org.hibernate.boot.archive.scan.spi.ClassDescriptor;
import org.hibernate.boot.archive.scan.spi.MappingFileDescriptor;
import org.hibernate.boot.archive.scan.spi.PackageDescriptor;
import org.hibernate.boot.archive.scan.spi.ScanEnvironment;
import org.hibernate.boot.archive.scan.spi.ScanResult;
import org.hibernate.boot.jaxb.Origin;
import org.hibernate.boot.jaxb.SourceType;
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService;
import org.hibernate.service.ServiceRegistry;

import org.jboss.logging.Logger;

/**
 * @author Steve Ebersole
 */
public class DeploymentResourcesInterpreter {
	/**
	 * Singleton access
	 */
	public static final DeploymentResourcesInterpreter INSTANCE = new DeploymentResourcesInterpreter();

	public static interface DeploymentResources {
		public Iterable<ClassDescriptor> getClassDescriptors();
		public Iterable<PackageDescriptor> getPackageDescriptors();
		public Iterable<MappingFileDescriptor> getMappingFileDescriptors();
	}

	private static final Logger log = Logger.getLogger( DeploymentResourcesInterpreter.class );

	public DeploymentResources buildDeploymentResources(
			ScanEnvironment scanEnvironment,
			ScanResult scanResult,
			ServiceRegistry serviceRegistry) {

		// mapping files ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

		final ArrayList<MappingFileDescriptor> mappingFileDescriptors = new ArrayList<MappingFileDescriptor>();

		final Set<String> nonLocatedMappingFileNames = new HashSet<String>();
		final List<String> explicitMappingFileNames = scanEnvironment.getExplicitlyListedMappingFiles();
		if ( explicitMappingFileNames != null ) {
			nonLocatedMappingFileNames.addAll( explicitMappingFileNames );
		}

		for ( MappingFileDescriptor mappingFileDescriptor : scanResult.getLocatedMappingFiles() ) {
			mappingFileDescriptors.add( mappingFileDescriptor );
			nonLocatedMappingFileNames.remove( mappingFileDescriptor.getName() );
		}

		for ( String name : nonLocatedMappingFileNames ) {
			MappingFileDescriptor descriptor = buildMappingFileDescriptor( name, serviceRegistry );
			mappingFileDescriptors.add( descriptor );
		}


		// classes and packages ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

		final HashMap<String, ClassDescriptor> classDescriptorMap = new HashMap<String, ClassDescriptor>();
		final HashMap<String, PackageDescriptor> packageDescriptorMap = new HashMap<String, PackageDescriptor>();

		for ( ClassDescriptor classDescriptor : scanResult.getLocatedClasses() ) {
			classDescriptorMap.put( classDescriptor.getName(), classDescriptor );
		}

		for ( PackageDescriptor packageDescriptor : scanResult.getLocatedPackages() ) {
			packageDescriptorMap.put( packageDescriptor.getName(), packageDescriptor );
		}

		final List<String> explicitClassNames = scanEnvironment.getExplicitlyListedClassNames();
		if ( explicitClassNames != null ) {
			for ( String explicitClassName : explicitClassNames ) {
				// IMPL NOTE : explicitClassNames can contain class or package names!!!
				if ( classDescriptorMap.containsKey( explicitClassName ) ) {
					continue;
				}
				if ( packageDescriptorMap.containsKey( explicitClassName ) ) {
					continue;
				}

				// try it as a class name first...
				final String classFileName = explicitClassName.replace( '.', '/' ) + ".class";
				final URL classFileUrl = serviceRegistry.getService( ClassLoaderService.class )
						.locateResource( classFileName );
				if ( classFileUrl != null ) {
					classDescriptorMap.put(
							explicitClassName,
							new ClassDescriptorImpl( explicitClassName, new UrlInputStreamAccess( classFileUrl ) )
					);
					continue;
				}

				// otherwise, try it as a package name
				final String packageInfoFileName = explicitClassName.replace( '.', '/' ) + "/package-info.class";
				final URL packageInfoFileUrl = serviceRegistry.getService( ClassLoaderService.class )
						.locateResource( packageInfoFileName );
				if ( packageInfoFileUrl != null ) {
					packageDescriptorMap.put(
							explicitClassName,
							new PackageDescriptorImpl( explicitClassName, new UrlInputStreamAccess( packageInfoFileUrl ) )
					);
					continue;
				}

				log.debugf(
						"Unable to resolve class [%s] named in persistence unit [%s]",
						explicitClassName,
						scanEnvironment.getRootUrl().toExternalForm()
				);
			}
		}

		return new DeploymentResources() {
			@Override
			public Iterable<ClassDescriptor> getClassDescriptors() {
				return classDescriptorMap.values();
			}

			@Override
			public Iterable<PackageDescriptor> getPackageDescriptors() {
				return packageDescriptorMap.values();
			}

			@Override
			public Iterable<MappingFileDescriptor> getMappingFileDescriptors() {
				return mappingFileDescriptors;
			}
		};
	}

	private MappingFileDescriptor buildMappingFileDescriptor(
			String name,
			ServiceRegistry serviceRegistry) {
		final URL url = serviceRegistry.getService( ClassLoaderService.class ).locateResource( name );
		if ( url == null ) {
			throw new MappingException(
					"Unable to resolve explicitly named mapping-file : " + name,
					new Origin( SourceType.RESOURCE, name )
			);
		}

		return new MappingFileDescriptorImpl( name, new UrlInputStreamAccess( url ) );
	}
}

/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.internal.atlas

import java.nio.file.FileStore
import java.nio.file.FileSystems

import org.sonatype.nexus.common.app.ApplicationDirectories
import org.sonatype.nexus.common.app.ApplicationLicense
import org.sonatype.nexus.common.app.ApplicationVersion
import org.sonatype.nexus.common.node.DeploymentAccess
import org.sonatype.nexus.common.node.NodeAccess

import org.apache.karaf.bundle.core.BundleService
import org.osgi.framework.BundleContext
import spock.lang.Specification

import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.core.IsEqual.equalTo
import static org.hamcrest.text.IsEmptyString.isEmptyString
import static org.hamcrest.core.IsNot.not

/**
 * Unit tests for {@link SystemInformationGeneratorImpl}
 */
class SystemInformationGeneratorImplTest
    extends Specification
{
  def "reportFileStores runs successfully using FileSystem.default"() {
    given:
      def generator = mockSystemInformationGenerator()

    when:
      def data = FileSystems.default.fileStores.collectEntries {
        [ (it.name()): generator.reportFileStore(it) ]
      }

    then:
      data.keySet().size()
      data.each { k, v ->
        assertThat(k, not(isEmptyString()))
        assertThat(v.description, not(isEmptyString()))
        assertThat(v.type, not(isEmptyString()))
        assertThat(v.totalSpace, not(isEmptyString()))
        assertThat(v.usableSpace, not(isEmptyString()))
        assertThat(v.readOnly, not(isEmptyString()))
      }
  }

  def "reportFileStores handles IOException gracefully"() {
    given:
      def generator = mockSystemInformationGenerator()

    when:
      def fs = Mock(FileStore) {
        toString() >> "description"
        type() >> "brokenfstype"
        name() >> "brokenfsname"
        getTotalSpace() >> { throw new IOException("testing") }
      }
      def fsReport = generator.reportFileStore(fs)

    then:
      fsReport == SystemInformationGeneratorImpl.UNAVAILABLE
  }

  def "reportNetwork runs successfully using NetworkInterface.networkInterfaces"() {
    given:
      def generator = mockSystemInformationGenerator()

    when:
      def data = NetworkInterface.networkInterfaces.toList().findAll {
        it.inetAddresses != null && it.inetAddresses.hasMoreElements()
      }.collectEntries { [(it.name): generator.reportNetworkInterface(it)] }
    
    then:
      data.keySet().size()
      data.each { k, v ->
        assertThat(k, not(isEmptyString()))
        assertThat(v.displayName, not(isEmptyString()))
        assertThat(v.up, not(isEmptyString()))
        assertThat(v.virtual, not(isEmptyString()))
        assertThat(v.multicast, not(isEmptyString()))
        assertThat(v.loopback, not(isEmptyString()))
        assertThat(v.ptp, not(isEmptyString()))
        assertThat(v.mtu, not(isEmptyString()))
        assertThat(v.addresses, not(isEmptyString()))
      }
  }

  def "reportNetwork handles SocketException gracefully"() {
    given:
      def generator = mockSystemInformationGenerator()

    when:
      def intf = GroovyMock(NetworkInterface) {
        getDisplayName() >> "brokenintf"
        supportsMulticast() >> { throw new SocketException("testing") }
      }
      def report = generator.reportNetworkInterface(intf)

    then:
      report == SystemInformationGeneratorImpl.UNAVAILABLE
  }

  def mockSystemInformationGenerator() {
    return new SystemInformationGeneratorImpl(
        Mock(ApplicationDirectories.class),
        Mock(ApplicationVersion.class),
        Mock(ApplicationLicense.class),
        Collections.emptyMap(),
        Mock(BundleContext.class),
        Mock(BundleService.class),
        Mock(NodeAccess.class),
        Mock(DeploymentAccess.class))
  }

  def "environment sensitive data is hidden"() {
    given:
      def generator = mockSystemInformationGenerator()
      // we need to exec some command to set up environment variables.
      def processBuilder = new ProcessBuilder('mvn', 'version')
      processBuilder.environment().put('AZURE_CLIENT_SECRET', 'azureSecretValue')
      processBuilder.environment().put('AZURE_TOKEN', 'azureTokenValue')
      processBuilder.environment().put('MY_PASSWORD_FOR_NXRM', 'admin123')
      processBuilder.start()

    when:
      def report = generator.report()

    then:
      def systemEnvs = report.get('system-environment')
      assertThat(systemEnvs.get('AZURE_CLIENT_SECRET'), not('azureSecretValue'))
      assertThat(systemEnvs.get('AZURE_TOKEN'), not('azureTokenValue'))
      assertThat(systemEnvs.get('MY_PASSWORD_FOR_NXRM'), not('admin123'))
  }

  def "jvm variable sensitive data is hidden"() {
    given:
      def generator = new SystemInformationGeneratorImpl(
          Mock(ApplicationDirectories.class),
          Mock(ApplicationVersion.class),
          Mock(ApplicationLicense.class),
          Collections.singletonMap("sun.java.command", "test.variable=1 -Dnexus.password=nxrm -Dnexus.token=123456"),
          Mock(BundleContext.class),
          Mock(BundleService.class),
          Mock(NodeAccess.class),
          Mock(DeploymentAccess.class))

    when:
      def report = generator.report()

    then:
      def nexusProps = report.get("nexus-properties")
      assertThat(nexusProps.get("sun.java.command"),
          equalTo("test.variable=1 -Dnexus.password=**** -Dnexus.token=****"))
  }

  def "INSTALL4J_ADD_VM_PARAMS sensitive data is hidden"() {
    given:
      def generator = new SystemInformationGeneratorImpl(
          Mock(ApplicationDirectories.class),
          Mock(ApplicationVersion.class),
          Mock(ApplicationLicense.class),
          Collections.singletonMap("INSTALL4J_ADD_VM_PARAMS", "test.variable=1 -Dnexus.password=nxrm -Dnexus.token=123456"),
          Mock(BundleContext.class),
          Mock(BundleService.class),
          Mock(NodeAccess.class),
          Mock(DeploymentAccess.class))

    when:
      def report = generator.report()

    then:
      def nexusProps = report.get("nexus-properties")
      assertThat(nexusProps.get("INSTALL4J_ADD_VM_PARAMS"),
          equalTo("test.variable=1 -Dnexus.password=**** -Dnexus.token=****"))
  }
}

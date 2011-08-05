// (c) Copyright 2011 Odiago, Inc.

package com.odiago.maven.plugins.hbase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/**
 * A maven goal that starts a mini HBase cluster in a new daemon thread.
 *
 * <p>A new daemon thread is created that starts a mini HBase cluster.  The main thread
 * blocks until the HBase cluster has full started.  The daemon thread with the
 * in-process HBase cluster will continue to run in the background until stopped by the
 * 'stop' goal of the plugin.</p>
 *
 * <p>The configuration of the started mini HBase cluster will be written to a
 * hbase-site.xml file in the test classpath ('${basedir}/target/test-classes' by
 * default).  The path to the generated configuration file may be customized with the
 * 'hbaseSiteFile' property</p>
 *
 * @goal start
 * @phase pre-integration-test
 * @requiresDependencyResolution test
 */
public class StartMojo extends AbstractMojo {
  /**
   * The file that will store the configuration required to connect to the started mini HBase
   * cluster.  This file will be generated by the goal.
   *
   * @parameter property="hbaseSiteFile" expression="${hbase.site.file}" default-value="${project.build.testOutputDirectory}/hbase-site.xml"
   * @required
   */
  private File mHBaseSiteFile;

  /**
   * If true, also start a mini MapReduce cluster.
   *
   * @parameter property="mapReduceEnabled" expression="${mapreduce.enabled}" default-value="false"
   */
  private boolean mIsMapReduceEnabled;

  /**
   * Extra Hadoop configuration properties to use.
   *
   * @parameter property="hadoopConfiguration"
   */
  private Properties mHadoopConfiguration;

  /**
   * A list of this plugin's dependency artifacts.
   *
   * @parameter default-value="${plugin.artifacts}"
   * @required
   * @readonly
   */
  private List<Artifact> mPluginDependencyArtifacts;


  /**
   * The maven project this plugin is running within.
   *
   * @parameter default-value="${project}"
   * @required
   * @readonly
   */
  private MavenProject mMavenProject;

  /**
   * Sets the file that we should write the HBase cluster configuration to.
   *
   * <p>Note: The property "hbaseSiteFile" defined in this mojo means this method must be
   * named setHbaseSiteFile instead of setHBaseSiteFile.</p>
   *
   * @param hbaseSiteFile The file we should write to.
   */
  public void setHbaseSiteFile(File hbaseSiteFile) {
    mHBaseSiteFile = hbaseSiteFile;
  }

  /**
   * Sets whether we should start a mini MapReduce cluster in addition to the HBase cluster.
   *
   * @param enabled Whether to start a mini MapReduce cluster.
   */
  public void setMapReduceEnabled(boolean enabled) {
    mIsMapReduceEnabled = enabled;
  }

  /**
   * Sets Hadoop configuration properties.
   *
   * @param properties Hadoop configuration properties to use in the mini cluster.
   */
  public void setHadoopConfiguration(Properties properties) {
    mHadoopConfiguration = properties;
  }

  /**
   * Starts a mini HBase cluster in a new thread.
   *
   * <p>This method is called by the maven plugin framework to run the goal.</p>
   *
   * @throws MojoExecutionException If there is a fatal error during this goal's execution.
   */
  @Override
  public void execute() throws MojoExecutionException {
    System.setProperty("java.class.path", getClassPath());
    getLog().info("Set java.class.path to: " + System.getProperty("java.class.path"));

    // Set any extra hadoop options.
    Configuration conf = new Configuration();
    if (null != mHadoopConfiguration) {
      for (Map.Entry<Object, Object> property : mHadoopConfiguration.entrySet()) {
        String confKey = property.getKey().toString();
        String confValue = property.getValue().toString();
        getLog().info("Setting hadoop conf property '" + confKey + "' to '" + confValue + "'");
        conf.set(confKey, confValue);
      }
    }

    // Start the cluster.
    try {
      MiniHBaseClusterSingleton.INSTANCE.startAndWaitUntilReady(
          getLog(), mIsMapReduceEnabled, conf);
    } catch (IOException e) {
      throw new MojoExecutionException("Unable to start HBase cluster.", e);
    }

    // Create an hbase conf file to write.
    File parentDir = mHBaseSiteFile.getParentFile();
    if (null != parentDir && !parentDir.exists() && !parentDir.mkdirs()) {
      throw new MojoExecutionException(
          "Unable to create hbase conf file: " + mHBaseSiteFile.getPath());
    }

    // Write the file.
    FileOutputStream fileOutputStream = null;
    try {
      fileOutputStream = new FileOutputStream(mHBaseSiteFile);
      conf.writeXml(fileOutputStream);
    } catch (IOException e) {
      throw new MojoExecutionException(
          "Unable to write to hbase conf file: " + mHBaseSiteFile.getPath(), e);
    } finally {
      if (null != fileOutputStream) {
        try {
          fileOutputStream.close();
        } catch (IOException e) {
          throw new MojoExecutionException(
              "Unable to close hbase conf file stream: " + mHBaseSiteFile.getPath(), e);
        }
      }
    }
    getLog().info("Wrote " + mHBaseSiteFile.getPath() + ".");
  }

  /**
   * Gets the runtime classpath required to run the mini clusters.
   *
   * <p>The maven classloading scheme is nonstandard.  They only put the "classworlds" jar
   * on the classpath, and it takes care of ClassLoading the rest of the jars.  This a
   * problem if we are going to start a mini MapReduce cluster.  The TaskTracker will
   * start a child JVM with the same classpath as this process, and it won't have
   * configured the classworlds class loader.  To work around this, we will put all of
   * our dependencies into the java.class.path system property, which will be read by
   * the TaskRunner's child JVM launcher to build the child JVM classpath.</p>
   *
   * <p>Note that when we say "all of our dependencies" we mean both the dependencies of
   * this plugin as well as the test classes and dependencies of the project that is
   * running the plugin.  We need to include the latter on the classpath because tests are
   * still just .class files at integration-test-time.  There will be no jars available
   * yet to put on the distributed cache via job.setJarByClass().  Hence, all of the
   * test-classes in the project running this plugin need to already be on the classpath
   * of the MapReduce cluster.<p>
   */
  private String getClassPath() throws MojoExecutionException {
    // Maintain a set of classpath components added so we can de-dupe.
    Set<String> alreadyAddedComponents = new HashSet<String>();

    // Use this to build up the classpath string.
    StringBuilder classpath = new StringBuilder();

    // Add the existing classpath.
    String existingClasspath = System.getProperty("java.class.path");
    classpath.append(existingClasspath);
    alreadyAddedComponents.addAll(Arrays.asList(existingClasspath.split(":")));

    // Add the test classes and dependencies of the maven project running this plugin.
    //
    // Note: It is important that we add these classes and dependencies before we add this
    // plugin's dependencies in case the maven project needs to override a jar version.
    List<?> testClasspathComponents;
    try {
      testClasspathComponents = mMavenProject.getTestClasspathElements();
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException("Unable to retrieve project test classpath", e);
    }
    for (Object testClasspathComponent : testClasspathComponents) {
      String dependency = testClasspathComponent.toString();
      if (alreadyAddedComponents.contains(dependency)) {
        continue;
      }
      classpath.append(":");
      classpath.append(dependency);
      alreadyAddedComponents.add(dependency);
    }

    // Add this plugin's dependencies.
    for (Artifact artifact : mPluginDependencyArtifacts) {
      String dependency = artifact.getFile().getPath();
      if (alreadyAddedComponents.contains(dependency)) {
        continue;
      }
      classpath.append(":");
      classpath.append(dependency);
      alreadyAddedComponents.add(dependency);
    }

    return classpath.toString();
  }
}

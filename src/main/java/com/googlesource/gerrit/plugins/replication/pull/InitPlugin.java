package com.googlesource.gerrit.plugins.replication.pull;

import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.GroupReference;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.pgm.init.api.AllProjectsConfig;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.InitStep;
import com.google.inject.Inject;
import org.eclipse.jgit.lib.Config;

public class InitPlugin implements InitStep {

  private final String pluginName;
  //  private final ProjectConfig.Factory projectConfigFactory;
  private final ConsoleUI ui;
  private final AllProjectsConfig allProjectsConfig;

  private static final String INTERNAL_USER = "Pull-replication Internal User";

  @Inject
  InitPlugin(@PluginName String pluginName, ConsoleUI ui, AllProjectsConfig allProjectsConfig) {
    this.pluginName = pluginName;
    //    this.projectConfigFactory = projectConfigFactory;
    this.ui = ui;
    this.allProjectsConfig = allProjectsConfig;
  }

  @Override
  public void run() throws Exception {
    ui.message(
        "Ensure pull-replication user has '%s' global capability\n",
        GlobalCapability.ACCESS_DATABASE);
    //
    //    ProjectConfig pc = projectConfigFactory.create(Project.nameKey("All-Projects"));

    Config cfg = allProjectsConfig.load().getConfig();
    cfg.setString("capability", null, GlobalCapability.ACCESS_DATABASE, "group " + INTERNAL_USER);

    allProjectsConfig
        .getGroups()
        .put(
            AccountGroup.UUID.parse("pullreplication:internal-user"),
            GroupReference.create(INTERNAL_USER));

    allProjectsConfig.save(pluginName, "Init step");

    //    pc.upsertAccessSection(
    //        AccessSection.GLOBAL_CAPABILITIES,
    //        as ->
    //            as.upsertPermission(GlobalCapability.ACCESS_DATABASE)
    //                .add(PermissionRule.fromString("group " + INTERNAL_USER, false).toBuilder()));
    ui.message(
        "'%s' global capability assigned to user '%s'\n",
        GlobalCapability.ACCESS_DATABASE, INTERNAL_USER);
  }
}

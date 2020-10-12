/*
 * SonarQube
 * Copyright (C) 2009-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.permission.ws.template;

import java.util.List;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.web.UserRole;
import org.sonar.core.util.SequenceUuidFactory;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ResourceTypesRule;
import org.sonar.db.permission.PermissionQuery;
import org.sonar.db.permission.template.PermissionTemplateDto;
import org.sonar.db.user.GroupDto;
import org.sonar.db.user.UserDto;
import org.sonar.server.es.TestProjectIndexers;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.permission.DefaultTemplatesResolver;
import org.sonar.server.permission.DefaultTemplatesResolverImpl;
import org.sonar.server.permission.PermissionTemplateService;
import org.sonar.server.permission.ws.BasePermissionWsTest;
import org.sonar.server.ws.TestRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.api.resources.Qualifiers.APP;
import static org.sonar.api.resources.Qualifiers.PROJECT;
import static org.sonar.api.resources.Qualifiers.VIEW;
import static org.sonar.db.permission.OrganizationPermission.SCAN;
import static org.sonarqube.ws.client.permission.PermissionsWsParameters.PARAM_PROJECT_ID;
import static org.sonarqube.ws.client.permission.PermissionsWsParameters.PARAM_PROJECT_KEY;
import static org.sonarqube.ws.client.permission.PermissionsWsParameters.PARAM_TEMPLATE_ID;
import static org.sonarqube.ws.client.permission.PermissionsWsParameters.PARAM_TEMPLATE_NAME;

public class ApplyTemplateActionTest extends BasePermissionWsTest<ApplyTemplateAction> {

  @Rule
  public DbTester dbTester = DbTester.create();

  private UserDto user1;
  private UserDto user2;
  private GroupDto group1;
  private GroupDto group2;
  private ComponentDto project;
  private PermissionTemplateDto template1;
  private PermissionTemplateDto template2;

  private ResourceTypesRule resourceTypesRule = new ResourceTypesRule().setRootQualifiers(PROJECT, VIEW, APP);
  private DefaultTemplatesResolver defaultTemplatesResolver = new DefaultTemplatesResolverImpl(dbTester.getDbClient(), resourceTypesRule);
  private PermissionTemplateService permissionTemplateService = new PermissionTemplateService(db.getDbClient(),
    new TestProjectIndexers(), userSession, defaultTemplatesResolver, new SequenceUuidFactory());

  @Override
  protected ApplyTemplateAction buildWsAction() {
    return new ApplyTemplateAction(db.getDbClient(), userSession, permissionTemplateService, newPermissionWsSupport());
  }

  @Before
  public void setUp() {
    user1 = db.users().insertUser();
    user2 = db.users().insertUser();
    group1 = db.users().insertGroup();
    group2 = db.users().insertGroup();

    // template 1
    template1 = db.permissionTemplates().insertTemplate();
    addUserToTemplate(user1, template1, UserRole.CODEVIEWER);
    addUserToTemplate(user2, template1, UserRole.ISSUE_ADMIN);
    addGroupToTemplate(group1, template1, UserRole.ADMIN);
    addGroupToTemplate(group2, template1, UserRole.USER);
    // template 2
    template2 = db.permissionTemplates().insertTemplate();
    addUserToTemplate(user1, template2, UserRole.USER);
    addUserToTemplate(user2, template2, UserRole.USER);
    addGroupToTemplate(group1, template2, UserRole.USER);
    addGroupToTemplate(group2, template2, UserRole.USER);

    project = db.components().insertPrivateProject();
    db.users().insertProjectPermissionOnUser(user1, UserRole.ADMIN, project);
    db.users().insertProjectPermissionOnUser(user2, UserRole.ADMIN, project);
    db.users().insertProjectPermissionOnGroup(group1, UserRole.ADMIN, project);
    db.users().insertProjectPermissionOnGroup(group2, UserRole.ADMIN, project);
  }

  @Test
  public void apply_template_with_project_uuid() {
    loginAsAdmin();

    newRequest(template1.getUuid(), project.uuid(), null);

    assertTemplate1AppliedToProject();
  }

  @Test
  public void apply_template_with_project_uuid_by_template_name() {
    loginAsAdmin();

    newRequest()
      .setParam(PARAM_TEMPLATE_NAME, template1.getName().toUpperCase())
      .setParam(PARAM_PROJECT_ID, project.uuid())
      .execute();

    assertTemplate1AppliedToProject();
  }

  @Test
  public void apply_template_with_project_key() {
    loginAsAdmin();

    newRequest(template1.getUuid(), null, project.getDbKey());

    assertTemplate1AppliedToProject();
  }

  @Test
  public void fail_when_unknown_template() {
    loginAsAdmin();

    expectedException.expect(NotFoundException.class);
    expectedException.expectMessage("Permission template with id 'unknown-template-uuid' is not found");

    newRequest("unknown-template-uuid", project.uuid(), null);
  }

  @Test
  public void fail_when_unknown_project_uuid() {
    loginAsAdmin();

    expectedException.expect(NotFoundException.class);
    expectedException.expectMessage("Project id 'unknown-project-uuid' not found");

    newRequest(template1.getUuid(), "unknown-project-uuid", null);
  }

  @Test
  public void fail_when_unknown_project_key() {
    loginAsAdmin();

    expectedException.expect(NotFoundException.class);
    expectedException.expectMessage("Project key 'unknown-project-key' not found");

    newRequest(template1.getUuid(), null, "unknown-project-key");
  }

  @Test
  public void fail_when_template_is_not_provided() {
    loginAsAdmin();

    expectedException.expect(BadRequestException.class);

    newRequest(null, project.uuid(), null);
  }

  @Test
  public void fail_when_project_uuid_and_key_not_provided() {
    loginAsAdmin();

    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Project id or project key can be provided, not both.");

    newRequest(template1.getUuid(), null, null);
  }

  @Test
  public void fail_when_not_admin() {
    userSession.logIn().addPermission(SCAN);

    expectedException.expect(ForbiddenException.class);

    newRequest(template1.getUuid(), project.uuid(), null);
  }

  private void assertTemplate1AppliedToProject() {
    assertThat(selectProjectPermissionGroups(project, UserRole.ADMIN)).containsExactly(group1.getName());
    assertThat(selectProjectPermissionGroups(project, UserRole.USER)).containsExactly(group2.getName());
    assertThat(selectProjectPermissionUsers(project, UserRole.ADMIN)).isEmpty();
    assertThat(selectProjectPermissionUsers(project, UserRole.CODEVIEWER)).containsExactly(user1.getUuid());
    assertThat(selectProjectPermissionUsers(project, UserRole.ISSUE_ADMIN)).containsExactly(user2.getUuid());
  }

  private void newRequest(@Nullable String templateUuid, @Nullable String projectUuid, @Nullable String projectKey) {
    TestRequest request = newRequest();
    if (templateUuid != null) {
      request.setParam(PARAM_TEMPLATE_ID, templateUuid);
    }
    if (projectUuid != null) {
      request.setParam(PARAM_PROJECT_ID, projectUuid);
    }
    if (projectKey != null) {
      request.setParam(PARAM_PROJECT_KEY, projectKey);
    }
    request.execute();
  }

  private void addUserToTemplate(UserDto user, PermissionTemplateDto permissionTemplate, String permission) {
    db.getDbClient().permissionTemplateDao().insertUserPermission(db.getSession(), permissionTemplate.getUuid(), user.getUuid(), permission);
    db.commit();
  }

  private void addGroupToTemplate(GroupDto group, PermissionTemplateDto permissionTemplate, String permission) {
    db.getDbClient().permissionTemplateDao().insertGroupPermission(db.getSession(), permissionTemplate.getUuid(), group.getUuid(), permission);
    db.commit();
  }

  private List<String> selectProjectPermissionGroups(ComponentDto project, String permission) {
    PermissionQuery query = PermissionQuery.builder().setPermission(permission).setComponent(project).build();
    return db.getDbClient().groupPermissionDao().selectGroupNamesByQuery(db.getSession(), query);
  }

  private List<String> selectProjectPermissionUsers(ComponentDto project, String permission) {
    PermissionQuery query = PermissionQuery.builder().setPermission(permission).setComponent(project).build();
    return db.getDbClient().userPermissionDao().selectUserUuidsByQuery(db.getSession(), query);
  }
}

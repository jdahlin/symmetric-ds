<%@ page import="org.jumpmind.symmetric.grails.NodeGroup" %>
<html>
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
  <meta name="layout" content="main"/>
  <g:set var="entityName" value="${message(code: 'nodeGroup.label', default: 'Group')}"/>
  <title><g:message code="default.create.label" args="[entityName]"/></title>
</head>
<body>

<div class="body">
<g:render template="/common/createMenu"/>
  <h1><g:message code="default.create.label" args="[entityName]"/></h1>
  <g:if test="${flash.message}">
    <div class="message">${flash.message}</div>
  </g:if>
  <g:hasErrors bean="${nodeGroupInstance}">
    <div class="errors">
      <g:renderErrors bean="${nodeGroupInstance}" as="list"/>
    </div>
  </g:hasErrors>
  <g:form name="form" controller="nodeGroup" action="save" method="post">
    <div class="dialog">
      <table>
        <tbody>
        <tr class="prop">
          <td valign="top" class="name">
            <label for="nodeGroupId"><g:message code="nodeGroup.nodeGroupId.label" default="Node Group Id"/></label>
          </td>
          <td valign="top" class="value ${hasErrors(bean: nodeGroupInstance, field: 'nodeGroupId', 'errors')}">
            <g:textField name="nodeGroupId" value="${nodeGroupInstance?.nodeGroupId}"/>
          </td>
        </tr>

        <tr class="prop">
          <td valign="top" class="name">
            <label for="description"><g:message code="nodeGroup.description.label" default="Description"/></label>
          </td>
          <td valign="top" class="value ${hasErrors(bean: nodeGroupInstance, field: 'description', 'errors')}">
            <g:textField name="description" value="${nodeGroupInstance?.description}"/>
          </td>
        </tr>
        </tbody>
      </table>
    </div>
  </g:form>
</div>
</body>
</html>
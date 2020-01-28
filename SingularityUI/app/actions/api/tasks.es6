import { buildApiAction, buildJsonApiAction } from './base';

export const FetchTasksInState = buildApiAction(
  'FETCH_TASKS',
  (state, renderNotFoundIf404, showResources) => {
    const stateToFetch = state !== 'decommissioning' ? state : 'active';
    let ids = ""
    let propertyString = '?property=';
    const propertyJoin = '&property=';

    switch (stateToFetch) {
      case 'active':
        if (showResources) {
          propertyString += ['offers', 'taskId', 'mesosTask.resources', 'rackId', 'taskRequest.request.requestType'].join(propertyJoin);
        } else {
          ids = "/ids"
          propertyString = '';
        }
        break;
      case 'scheduled':
        ids = "/ids"
        propertyString = '';
        break;
      default:
        propertyString = '';
    }

    if (propertyString.includes('?')) {
      propertyString = propertyString + '&useWebCache=true'
    } else {
      propertyString = '?useWebCache=true'
    }

    return {
      url: `/tasks/${stateToFetch}${ids}${propertyString}`,
      renderNotFoundIf404
    };
  }
);

export const FetchScheduledTasksForRequest = buildApiAction(
  'FETCH_SCHEDULED_TASKS_FOR_REQUEST',
  (requestId) => ({
    url: `/tasks/scheduled/request/${requestId}?useWebCache=true`
  }),
  (requestId) => requestId
);

export const FetchTask = buildApiAction(
  'FETCH_TASK',
  (taskId) => ({
    url: `/tasks/task/${taskId}?useWebCache=true`,
  })
);

export const KillTask = buildJsonApiAction(
  'KILL_TASK',
  'DELETE',
  (taskId, data) => ({
    url: `/tasks/task/${taskId}`,
    body: data
  })
);

export const FetchTaskCleanups = buildApiAction(
  'FETCH_TASK_CLEANUPS',
  {url: '/tasks/cleaning?useWebCache=true'}
);

export const FetchTaskStatistics = buildApiAction(
  'FETCH_TASK_STATISTICS',
  (taskId, catchStatusCodes) => ({
    url: `/tasks/task/${taskId}/statistics`,
    catchStatusCodes
  })
);

export const RunCommandOnTask = buildJsonApiAction(
  'RUN_COMMAND_ON_TASK',
  'POST',
  (taskId, commandName) => ({
    url: `/tasks/task/${taskId}/command`,
    body: {name: commandName}
  })
);

export const DeletePendingOnDemandTask = buildJsonApiAction(
  'DELETE_PENDING_ON_DEMAND_TASK',
  'DELETE',
  (taskId) => ({
    url: `/tasks/scheduled/task/${taskId}`
  })
);

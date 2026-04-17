/**
 * Webhook handlers for various services.
 * Formats webhook payloads into notification title/body.
 */

export function formatGitHubWebhook(payload, event) {
  const repo = payload.repository?.full_name || 'unknown';

  switch (event) {
    case 'push': {
      const branch = (payload.ref || '').replace('refs/heads/', '');
      const commits = payload.commits || [];
      const pusher = payload.pusher?.name || 'someone';
      const lines = commits
        .slice(0, 5)
        .map((c) => `• ${c.message.split('\n')[0]}`);
      if (commits.length > 5) lines.push(`... and ${commits.length - 5} more`);
      return {
        title: `[${repo}] Push to ${branch}`,
        body: `${pusher} pushed ${commits.length} commit(s)\n${lines.join('\n')}`,
        url: payload.compare || payload.repository?.html_url,
      };
    }

    case 'pull_request': {
      const pr = payload.pull_request || {};
      const action = payload.action || 'updated';
      const user = pr.user?.login || 'someone';
      return {
        title: `[${repo}] PR #${pr.number} ${action}`,
        body: `${user}: ${pr.title || ''}`,
        url: pr.html_url,
      };
    }

    case 'issues': {
      const issue = payload.issue || {};
      const action = payload.action || 'updated';
      const user = issue.user?.login || 'someone';
      return {
        title: `[${repo}] Issue #${issue.number} ${action}`,
        body: `${user}: ${issue.title || ''}`,
        url: issue.html_url,
      };
    }

    case 'issue_comment': {
      const issue = payload.issue || {};
      const comment = payload.comment || {};
      const user = comment.user?.login || 'someone';
      return {
        title: `[${repo}] Comment on #${issue.number}`,
        body: `${user}: ${(comment.body || '').substring(0, 200)}`,
        url: comment.html_url,
      };
    }

    case 'release': {
      const release = payload.release || {};
      const action = payload.action || 'published';
      return {
        title: `[${repo}] Release ${action}`,
        body: `${release.tag_name || ''}: ${release.name || ''}`,
        url: release.html_url,
      };
    }

    case 'star':
    case 'watch': {
      const action = payload.action || 'starred';
      const user = payload.sender?.login || 'someone';
      return {
        title: `[${repo}] ${action}`,
        body: `${user} ${action} the repository`,
        url: payload.repository?.html_url,
      };
    }

    default:
      return {
        title: `[${repo}] GitHub: ${event}`,
        body: JSON.stringify(payload).substring(0, 200),
        url: payload.repository?.html_url,
      };
  }
}

export function formatGitLabWebhook(payload, event) {
  const project = payload.project?.path_with_namespace || 'unknown';

  switch (event) {
    case 'Push Hook':
    case 'push': {
      const branch = (payload.ref || '').replace('refs/heads/', '');
      const commits = payload.commits || [];
      const user = payload.user_name || 'someone';
      const lines = commits
        .slice(0, 5)
        .map((c) => `• ${c.message.split('\n')[0]}`);
      if (commits.length > 5) lines.push(`... and ${commits.length - 5} more`);
      return {
        title: `[${project}] Push to ${branch}`,
        body: `${user} pushed ${commits.length} commit(s)\n${lines.join('\n')}`,
        url: payload.project?.web_url,
      };
    }

    case 'Merge Request Hook':
    case 'merge_request': {
      const mr = payload.object_attributes || {};
      const user = payload.user?.name || 'someone';
      return {
        title: `[${project}] MR !${mr.iid} ${mr.action || 'updated'}`,
        body: `${user}: ${mr.title || ''}`,
        url: mr.url,
      };
    }

    case 'Issue Hook':
    case 'issue': {
      const issue = payload.object_attributes || {};
      const user = payload.user?.name || 'someone';
      return {
        title: `[${project}] Issue #${issue.iid} ${issue.action || 'updated'}`,
        body: `${user}: ${issue.title || ''}`,
        url: issue.url,
      };
    }

    case 'Pipeline Hook':
    case 'pipeline': {
      const pipeline = payload.object_attributes || {};
      const status = pipeline.status || 'unknown';
      return {
        title: `[${project}] Pipeline #${pipeline.id} ${status}`,
        body: `Branch: ${pipeline.ref || 'unknown'}, Status: ${status}`,
        url: `${payload.project?.web_url}/-/pipelines/${pipeline.id}`,
      };
    }

    default:
      return {
        title: `[${project}] GitLab: ${event}`,
        body: JSON.stringify(payload).substring(0, 200),
        url: payload.project?.web_url,
      };
  }
}

export function formatDockerWebhook(payload) {
  const repo = payload.repository?.repo_name || 'unknown';
  const tag = payload.push_data?.tag || 'latest';
  const pusher = payload.push_data?.pusher || 'someone';

  return {
    title: `[Docker Hub] ${repo}:${tag}`,
    body: `${pusher} pushed a new image to ${repo}:${tag}`,
    url: payload.repository?.repo_url
      ? `https://hub.docker.com/r/${repo}`
      : undefined,
  };
}

export function formatGiteaWebhook(payload, event) {
  const repo = payload.repository?.full_name || 'unknown';

  switch (event) {
    case 'push': {
      const branch = (payload.ref || '').replace('refs/heads/', '');
      const commits = payload.commits || [];
      const pusher = payload.pusher?.login || payload.pusher?.username || 'someone';
      const lines = commits
        .slice(0, 5)
        .map((c) => `• ${c.message.split('\n')[0]}`);
      if (commits.length > 5) lines.push(`... and ${commits.length - 5} more`);
      return {
        title: `[${repo}] Push to ${branch}`,
        body: `${pusher} pushed ${commits.length} commit(s)\n${lines.join('\n')}`,
        url: payload.compare_url || payload.repository?.html_url,
      };
    }

    case 'pull_request': {
      const pr = payload.pull_request || {};
      const action = payload.action || 'updated';
      const user = pr.user?.login || 'someone';
      return {
        title: `[${repo}] PR #${pr.number} ${action}`,
        body: `${user}: ${pr.title || ''}`,
        url: pr.html_url,
      };
    }

    case 'issues': {
      const issue = payload.issue || {};
      const action = payload.action || 'updated';
      const user = issue.user?.login || 'someone';
      return {
        title: `[${repo}] Issue #${issue.number} ${action}`,
        body: `${user}: ${issue.title || ''}`,
        url: issue.html_url,
      };
    }

    default:
      return {
        title: `[${repo}] Gitea: ${event}`,
        body: JSON.stringify(payload).substring(0, 200),
        url: payload.repository?.html_url,
      };
  }
}

export function formatGenericWebhook(payload) {
  // Try to extract meaningful title/body from generic payloads
  const title =
    payload.title ||
    payload.subject ||
    payload.name ||
    payload.event ||
    'Webhook Notification';
  const body =
    payload.body ||
    payload.message ||
    payload.text ||
    payload.content ||
    payload.description ||
    JSON.stringify(payload).substring(0, 300);
  const url = payload.url || payload.link || payload.html_url;

  return { title, body: String(body).substring(0, 500), url };
}

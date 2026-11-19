import { randomUUID } from 'node:crypto'

import { expect, type APIRequestContext, type Page } from '@playwright/test'

import { API, BUYER, psql, type Session } from './trade-fixtures'

// Customer service (step G1). The counterparts have no pages: their operators and the platform
// support answer through the authorised desk routes, as infra/scripts/dev-support-reply.cjs does.
export const ROBOT_MERCHANT = 'c1000000-0000-4000-8000-000000000001'
export const YUNYI_PROVIDER = '22222222-2222-2222-2222-222222222222'
export const PROVIDER_DESK = '/api/v1/provider/conversations'
export const PLATFORM_DESK = '/api/v1/platform/support/conversations'

const buyerId = `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

/** How many of the buyer's conversations already hold messages (a run must not touch them). */
export function buyerConversationsWithMessages(): number {
  return Number(psql(`SELECT count(*) FROM support_conversation c WHERE c.customer_id = ${buyerId}
    AND EXISTS (SELECT 1 FROM support_message m WHERE m.conversation_id = c.id);`))
}

/**
 * Removes the buyer's conversations that a run made (created since `since`, or left empty), with
 * their messages and 客服消息 notifications, and the buyer's feedback since `since`.
 */
export function purgeBuyerSupport(since: string): void {
  psql(`
    BEGIN;
    CREATE TEMP TABLE e2e_conversations ON COMMIT DROP AS
      SELECT c.id FROM support_conversation c WHERE c.customer_id = ${buyerId}
        AND (c.created_at >= '${since}'::timestamptz
          OR NOT EXISTS (SELECT 1 FROM support_message m WHERE m.conversation_id = c.id));
    DELETE FROM notification_message WHERE category = 'CUSTOMER_SERVICE' AND target_type = 'CONVERSATION'
      AND target_id IN (SELECT id FROM e2e_conversations);
    DELETE FROM support_message WHERE conversation_id IN (SELECT id FROM e2e_conversations);
    DELETE FROM support_conversation WHERE id IN (SELECT id FROM e2e_conversations);
    DELETE FROM support_feedback WHERE account_id = ${buyerId} AND created_at >= '${since}'::timestamptz;
    COMMIT;
  `)
}

export function conversationIdFromUrl(page: Page): string {
  return new URL(page.url().replace('/#/', '/')).searchParams.get('conversationId') as string
}

export async function deskReply(
  request: APIRequestContext,
  desk: Session,
  route: string,
  conversationId: string,
  text: string,
): Promise<void> {
  const reply = await request.post(`${API}${route}/${conversationId}/messages`, {
    headers: { ...desk.headers, 'Idempotency-Key': randomUUID() },
    data: { text },
  })
  expect(reply.status(), await reply.text()).toBe(201)
}

/**
 * Answers the chat's conversation routes with one fixed conversation, for specs that only check
 * where a button leads and should leave nothing in the development database. Returns the bodies of
 * the open requests.
 */
export async function stubSupportChat(
  page: Page,
  counterpart: { kind: 'PLATFORM' | 'MERCHANT' | 'PROVIDER'; organizationId: string | null; name: string },
): Promise<unknown[]> {
  const opened: unknown[] = []
  const conversation = {
    conversationId: '6f1b2c3d-1111-4222-8333-444455556666',
    counterpart,
    lastMessage: null,
    unreadCount: 0,
    createdAt: '2026-11-19T02:00:00Z',
  }
  await page.route('**/api/v1/conversations', (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    opened.push(route.request().postDataJSON())
    return route.fulfill({ status: 201, json: conversation })
  })
  await page.route('**/api/v1/conversations/*/messages?*', (route) =>
    route.fulfill({ json: { conversation, items: [], hasMore: false, nextBefore: null } }))
  await page.route('**/api/v1/conversations/*/read', (route) => route.fulfill({ json: conversation }))
  return opened
}

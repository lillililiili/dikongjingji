import { BUYER, psql } from './trade-fixtures'

const buyerId = `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

/**
 * Deletes dev-customer's free enrolments (those without a trade order) with their tickets and
 * events, and the children the tests removed through the API (status DELETED, kept by the server
 * because tickets pointed at them). Tickets still holding a place give it back to their session first.
 *
 * `onlyCancelled`: just the enrolments that are over (status CANCELLED, every ticket cancelled), which
 * hold no seats; for specs that must not touch a free enrolment another spec may still be using.
 */
export function purgeFreeEnrollments(options: { onlyCancelled?: boolean } = {}): void {
  const scope = options.onlyCancelled
    ? `AND e.status = 'CANCELLED'
       AND NOT EXISTS (SELECT 1 FROM activity_enrollment_ticket t WHERE t.enrollment_id = e.id AND t.status <> 'CANCELLED')`
    : ''
  psql(`
    BEGIN;
    CREATE TEMP TABLE e2e_free_enrollments ON COMMIT DROP AS
      SELECT e.id FROM activity_enrollment e
       WHERE e.account_id = ${buyerId} AND e.trade_order_id IS NULL ${scope};
    SELECT count(*) FROM (SELECT id FROM activity_enrollment WHERE id IN (SELECT id FROM e2e_free_enrollments) FOR UPDATE) locked;
    UPDATE activity_session s SET enrolled_count = s.enrolled_count - t.n
      FROM (SELECT session_id, count(*) AS n FROM activity_enrollment_ticket
            WHERE enrollment_id IN (SELECT id FROM e2e_free_enrollments) AND status <> 'CANCELLED' GROUP BY session_id) t
     WHERE s.id = t.session_id;
    DELETE FROM activity_enrollment_event WHERE enrollment_id IN (SELECT id FROM e2e_free_enrollments);
    DELETE FROM activity_enrollment_ticket WHERE enrollment_id IN (SELECT id FROM e2e_free_enrollments);
    DELETE FROM activity_enrollment WHERE id IN (SELECT id FROM e2e_free_enrollments);
    DELETE FROM activity_participant p WHERE p.owner_id = ${buyerId} AND p.status = 'DELETED'
      AND NOT EXISTS (SELECT 1 FROM activity_enrollment_ticket t WHERE t.participant_id = p.id);
    COMMIT;
  `)
}

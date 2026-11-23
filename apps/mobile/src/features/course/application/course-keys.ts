import { createTradeKey } from '../../trade/application/trade-keys'

// A random UUID for the Idempotency-Key of a purchase; a retry of the same tap sends the same one.
export const createCourseKey = (): string => createTradeKey()

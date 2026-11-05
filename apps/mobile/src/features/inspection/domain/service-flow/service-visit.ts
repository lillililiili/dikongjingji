export type ServiceVisitStatus =
  | 'PENDING_SCHEDULE'
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'DELIVERED'
  | 'ACCEPTED'

export interface ServiceVisitLike {
  id: string
  visitNo: number
  status: ServiceVisitStatus
}

function byVisitNo<Visit extends ServiceVisitLike>(visits: Visit[]): Visit[] {
  return [...visits].sort((left, right) => left.visitNo - right.visitNo)
}

export function isVisitFinished(visit: ServiceVisitLike): boolean {
  return visit.status === 'DELIVERED' || visit.status === 'ACCEPTED'
}

export function findPendingAcceptanceVisit<Visit extends ServiceVisitLike>(
  visits: Visit[],
): Visit | null {
  return byVisitNo(visits).find((visit) => visit.status === 'DELIVERED') ?? null
}

export function findNextVisit<Visit extends ServiceVisitLike>(
  visits: Visit[],
): Visit | null {
  return byVisitNo(visits).find((visit) =>
    visit.status === 'PENDING_SCHEDULE' || visit.status === 'SCHEDULED') ?? null
}

// A link from before per-visit delivery has no visitId: open what still needs
// acceptance, otherwise the latest visit that has a delivery.
export function resolveDeliveryVisit<Visit extends ServiceVisitLike>(
  visits: Visit[],
  visitId: string,
): Visit | null {
  if (visitId) return visits.find((visit) => visit.id === visitId) ?? null
  const finished = byVisitNo(visits).filter(isVisitFinished)
  return findPendingAcceptanceVisit(visits) ?? finished[finished.length - 1] ?? null
}

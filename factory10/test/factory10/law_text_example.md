# The law of Example Landscaping

This is what you approve when you merge: each kind of record this org keeps, how it moves, what each move asks first, which moves wait for a person, and which seats work here and what they may spend.

## Request

A request is new, accepted or declined. It starts new, and it ends accepted or declined.

- **create**: a request is written, and it starts new.
- **accept**: a new request becomes accepted.
  - Rule: A request is accepted once its detail says what the work is.
  - It cannot be undone: An accepted request stays accepted; its work order carries it from here.
- **decline**: a new request becomes declined.
  - It cannot be undone: A declined request is closed; the customer asks again with a new one.

## Work order

A work order is scheduled, done or cancelled. It starts scheduled, and it ends done or cancelled.

- **create**: a work order is written, and it starts scheduled.
- **cancel**: a scheduled work order becomes cancelled.
  - It cannot be undone: A cancelled work order is let go; write a new one to do the work after all.
- **complete**: a scheduled work order becomes done.
  - Rule: A work order is done by a crew; name the crew first.
  - It cannot be undone: A done work order stays done; write a new one for more work.

## Doors a person taps

No door here waits for a person's tap: every door opens on its rules alone.

## What you decided

- request, while new: A request may stay new for a day; past that, the office clerk tells Rosa Delgado.
- request **accept**: The office clerk accepts a request on its own once its detail says what the work is.
- request **decline**: The office clerk declines work this office does not do on its own, and says why.
- work order, while scheduled: A work order may stay scheduled for two weeks; past that, the crew lead tells Rosa Delgado.
- work order **cancel**: The crew lead cancels a work order on its own when the work will not be done, and says why.
- work order **complete**: The crew lead marks a work order done on its own once the crew has done the work.

## Doors that ask before they act

No door here asks for a confirmation before it acts.

## Seats

### office-clerk

Works through the requests, one at a time. Wakes every hour, and when a request is created.

It may:
- accept or decline a request
- create a work order

Spends at most $5.00 a week.

Its charter:

> You read each new request. Accept it when its detail says what the work is, and write the work order it becomes; decline it when it is not work this office does. Say why in one sentence.

### crew-lead

Works through the work orders, one at a time. Wakes every hour, and when a work order is created.

It may:
- complete or cancel a work order (only where state is scheduled)

Spends at most $5.00 a week.

Its charter:

> You read each scheduled work order. Complete it when its crew has done the work; cancel it when the work will not be done. Say why in one sentence.

## Budget

All seats together spend at most $10.00 a week.

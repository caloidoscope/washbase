# Product Vision & Claude Guidance: Washbase

## 1. Product Overview
**Washbase** is a foundational operations management platform for laundry businesses. It provides seamless status visibility and flexible service modes for clients, alongside straightforward client, order, employee task handling, and pricing management for business owners and shop staff.

### Key Service Modes (Client-Facing)
- **Drop-off**: Client drops off garments at the shop.
- **Pickup**: Staff picks up laundry from client location.
- **Delivery**: Staff delivers clean laundry to client location.

---

## 2. Core User Personas
- **Client**: Wants visibility into laundry progress, service convenience (pickup/delivery/drop-off), and flexible payment options under their chosen laundry shop's brand.
- **Employee / Staff** (called **Staff** in Epics, Features and tests): Wants a simple operational interface to intake laundry, process payments, and update order statuses smoothly as items move through processing.
- **Owner / Admin**: Wants to manage client and employee accounts, configure pricing, customize business branding/logos, and oversee overall operations.

---

## 3. Key Domain Rules & Workflows

### Order Status Lifecycle
All orders move through the following sequential operational statuses, driven primarily by staff actions:  
`Queued` ➔ `Washing` ➔ `Drying` ➔ `Ready for Pickup` / `Out for Delivery` ➔ `Completed`

### Payment Processing
- **Supported Payment Methods**: Cash, GCash, Card, QRPh / InstaPay.
- **Decoupled Workflow Rule**: Payment status (`Paid` / `Unpaid`) does **NOT** block or restrict order status progression. Orders can move through processing regardless of payment settlement.

### Branding & Customization
- **Custom Branding**: The owner uploads and configures the business's own logo (a runtime setting of this deployment's single business; see Platform & Deployment Decisions), which dynamically renders across both the **Admin/Staff portal** and the **Customer-facing client interface**.

---

## 4. Product Roadmap & Scoping

### Phase 1: MVP (Current Scope)
*Focus strictly on core operational mechanics, staff order handling, client tracking, branding customization, basic management, and payment options (integrated and recorded).*
- **Custom Branding**: Business owner logo customization for Admin/Staff and Customer interfaces
- **Client Service Selection**: Drop-off, Pickup, Delivery requests
- **Employee / Staff Operations**: Order intake, manually updating status progression, logging payments
- **Order Tracking**: Real-time client status visibility
- **Owner Management**: Client/Staff user management, service pricing configuration, business profile
- **Payment Options**: Integrated payments through a provider (GCash, QRPh/InstaPay, Card), plus recorded payments (Cash, direct GCash). See Platform & Deployment Decisions → Payments.

### Phase 2: Future / Post-MVP (Out of Scope for MVP)
*Features marked for future enhancement. Claude should not generate MVP PBIs for these unless explicitly requested.*
- **Lost and Found Module**: Logging unassigned or stray items left in machines/orders and matching them to client inquiries or claims.
- **Advanced Dashboards & Analytics**: Business reports, revenue tracking, peak hours metrics.
- **Automated Notifications**: SMS / Push notifications for status changes.
- **Driver / Route Management**: Real-time driver tracking for pickup and delivery.
- **Inventory & Supply Tracking**: Soap, detergent, and equipment management.
- **Multi-Branch Operations**: Managing multiple store locations under a single account.

---

## Platform & Deployment Decisions

### One business per deployment
- Each deployment serves **one laundry business**. Washbase is reused for other businesses by **forking the repository** per client, not by hosting many businesses in one system.
- So: **no multi-tenant data model** (no tenant IDs or cross-business isolation). The business's name, logo, branding and pricing are **configured by the owner at runtime** (data, not code), so a new client usually needs a new deployment, not code changes.
- Keep the core generic: put client-specific changes in clearly separated places so forks can keep pulling improvements from this repository.

### Every persona on every app
- Clients, staff and owners can all use **both the web app and the mobile app**. Both apps show screens by role after login.
- Any Feature with a UI is built for **web and mobile** unless the Feature says otherwise.

### Payments
- **Integrated (real money movement through a payment provider):** GCash, Card, QRPh / InstaPay. The provider is not chosen yet; choosing it (and opening a merchant account) is a prerequisite for the payment Features.
- **Recorded only (staff log it, no money moves through the system):** Cash, and GCash sent directly to the shop's own account outside the integration.
- Both kinds produce the same `Paid` / `Unpaid` status on the order. The Decoupled Workflow Rule still applies: payment never blocks order status.

---

## 5. Backlog Generation Rules for Claude

When generating backlog items, strictly follow this 2-tier hierarchy and formatting structure:

### Tier 1: Epics & Features
Epics represent broad, high-level functional modules. Break Epics down into modular, behavior-specific **Features**.

**Epic Format:**
```markdown
# Epic: [Epic Title / Functional Module]

## User Intent
**As a** [Persona: Client / Staff / Owner]  
**I want to** [high-level goal or action]  
**So that** [business or user outcome]  

## In-Scope Features
- Feature 1: [Brief summary]
- Feature 2: [Brief summary]
```

### Tier 2: Features
Each Feature is one behavior, small enough to build, test and ship in one pull request. Acceptance criteria use **Given / When / Then** scenarios: each scenario becomes one automated test named after it (API and web), so the human can read the criteria and the tests prove them. Until a mobile end-to-end harness exists, mobile-only scenarios are checked manually on a device (Expo Go) and listed by name in the pull request.

**Feature Format:**
```markdown
# Feature: [One specific behavior, e.g. "Staff updates an order's status"]
**Epic:** [Linear ID] · **Personas:** Client / Staff / Owner · **Platforms:** web + mobile (default) / web only / mobile only

## User Story
**As a** [persona] **I want to** [action] **so that** [outcome]

## Acceptance Criteria
### Scenario: [Main success path]
Given [starting situation, with concrete values]
When [the user does one thing]
Then [what they see / what changes]
And [anything else that must be true]

### Scenario: [Error or edge case]
Given … When … Then …

### Scenario Outline: [A rule with several cases]
Given an order of <weight> kg using <service>
When staff confirm the order
Then the total is <total>

| weight | service  | total |
|--------|----------|-------|
| 3      | Drop-off | ₱150  |
| 3      | Pickup   | ₱200  |

## Business Rules
- [Plain-language rules, e.g. "Payment status never blocks order status"]

## Out of Scope
- [What this Feature deliberately leaves out]

## Technical Notes *(filled in by the Product Owner; optional reading for the human)*
- **API:** endpoints needed
- **Data:** new or changed records
- **UI states:** loading, empty, error, success, per platform
```

### Scenario writing rules
- **Concrete values**: "3 kg" and "₱150", never "some weight" or "the correct price".
- **One behavior per scenario**: if it needs several "When" steps, split it.
- **The user's language**: "the client sees *Washing*", not "the API returns status=WASHING".
- **Cover unhappy paths**: invalid input, nothing found, no permission.
- **Use a Scenario Outline with an examples table** when one rule has several cases (pricing, status transitions, permissions by role).

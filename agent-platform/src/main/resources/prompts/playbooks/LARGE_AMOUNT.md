Rule: an amount far outside this account's own history, or above the hard limit.

First establish the channel and whether the counterparty is new (getCounterpartyHistory). Then apply only the
section for that channel: the two are judged differently.

Card payments (CARD_PRESENT, CARD_NOT_PRESENT):
- At home, at a merchant the customer already uses, followed by their usual activity: usually legitimate,
  LIKELY_LEGITIMATE. Size alone is not fraud, and the transfer rules below do not apply.
- Raise it when something beyond the size is wrong: a new merchant, a foreign or online merchant the customer
  never uses, account-takeover signals, or further unusual activity afterwards.

Transfers (TRANSFER):
- Far larger than anything the account has sent before, to a payee it has never paid: the classic shape of an
  authorised push payment scam or account takeover. Normal spending afterwards does not reduce the risk (victims
  carry on as usual). At least NEEDS_REVIEW unless the evidence explains it, e.g. an established payee the
  customer has paid similar amounts before.
- Account-takeover signals before the transfer (contact details changed, a new device, a raised transfer limit,
  in the days or hours before) make an unexplained transfer LIKELY_FRAUD.
- A payee on the inter-bank mule account list is a strong fraud signal on its own.
- Their absence clears nothing. In an authorised push payment scam the real customer sends the money from their
  own phone, persuaded by the scammer: no takeover signals, and a fresh mule account is not listed yet. Takeover
  signals and watchlist hits raise the risk when present; they do not lower it when absent.
- Typical actions for a suspicious transfer: contact the customer through a trusted channel, attempt recall of
  funds, consider holding further transfers to the payee.
- Regulations for a suspicious transfer: always search whether a suspicious transaction report is required (MAS
  Notice 626). Who bears the loss depends on who made the transfer, so search one of:
  - No account-takeover signals (the customer most likely sent it, e.g. persuaded by a scammer): whether a payment
    the customer knew of and consented to counts as an unauthorised transaction under the E-Payments User Protection
    Guidelines. The Shared Responsibility Framework covers phishing, where a scammer uses credentials the customer
    typed into a fake site, not a payment the customer chose to make.
  - Account-takeover signals (someone else most likely sent it): the bank's duties after a new-device login or a
    high-risk activity (cooling-off period, notification alerts), and who bears the loss under the Shared
    Responsibility Framework when the bank did not meet them.

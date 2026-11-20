import { BadRequestException, Injectable } from '@nestjs/common';
import { Prisma, EntryDirection } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.module';
@Injectable()
export class LedgerService {
  constructor(private prisma: PrismaService) {}
  async creditWalletFromVerifiedDeposit(userId: string, reference: string, amount: string, currency: string) {
    const value = new Prisma.Decimal(amount);
    if (!value.isFinite() || value.lte(0)) throw new BadRequestException('Invalid ledger amount.');
    return this.prisma.$transaction(async tx => {
      const payment = await tx.paymentTransaction.findUnique({ where: { txRef: reference } });
      if (!payment || payment.userId !== userId || payment.status === 'SUCCEEDED') return;
      if (payment.currency !== currency || !payment.amount.equals(value)) throw new BadRequestException('Verified payment does not match the original transaction.');
      const wallet = await tx.wallet.findUnique({ where: { userId }, include: { ledgerAccount: true } });
      if (!wallet?.ledgerAccount) throw new BadRequestException('Wallet ledger account is missing.');
      let clearing = await tx.ledgerAccount.findUnique({ where: { code: 'flutterwave:clearing:NGN' } });
      if (!clearing) clearing = await tx.ledgerAccount.create({ data: { code: 'flutterwave:clearing:NGN', type: 'FLUTTERWAVE_CLEARING', currency } });
      await tx.journalTransaction.create({ data: {
        reference: 'deposit:' + reference, description: 'Verified Flutterwave deposit', currency,
        entries: { create: [
          { accountId: clearing.id, direction: EntryDirection.DEBIT, amount: value },
          { accountId: wallet.ledgerAccount.id, direction: EntryDirection.CREDIT, amount: value },
        ] },
      } });
      await tx.paymentTransaction.update({ where: { id: payment.id }, data: { status: 'SUCCEEDED' } });
    }, { isolationLevel: Prisma.TransactionIsolationLevel.Serializable });
  }
}

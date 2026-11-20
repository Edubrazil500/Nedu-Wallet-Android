import { Injectable, NotFoundException } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.module';
@Injectable()
export class WalletService {
  constructor(private prisma: PrismaService) {}
  async getWallet(userId: string) {
    const wallet = await this.prisma.wallet.findUnique({ where: { userId }, include: { ledgerAccount: { include: { entries: true } } } });
    if (!wallet) throw new NotFoundException('Wallet not found.');
    const balance = (wallet.ledgerAccount?.entries ?? []).reduce((total, e) => total + (e.direction === 'CREDIT' ? Number(e.amount) : -Number(e.amount)), 0);
    return { id: wallet.id, currency: wallet.currency, status: wallet.status, availableBalance: balance.toFixed(2) };
  }
}

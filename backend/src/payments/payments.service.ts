import { BadRequestException, Injectable, ServiceUnavailableException } from '@nestjs/common';
import { randomUUID } from 'crypto';
import { PrismaService } from '../prisma/prisma.module';
import { FlutterwaveService } from './flutterwave.service';
@Injectable()
export class PaymentsService {
  constructor(private prisma: PrismaService, private flutterwave: FlutterwaveService) {}
  async initializeDeposit(userId: string, amount: number, email: string) {
    if (!Number.isFinite(amount) || amount < 100 || amount > 5000000 || Math.round(amount * 100) !== amount * 100) throw new BadRequestException('Deposit must be NGN 100–5,000,000 with at most two decimal places.');
    const txRef = 'NEDU-' + randomUUID();
    await this.prisma.paymentTransaction.create({ data: { userId, txRef, kind: 'DEPOSIT', amount, currency: 'NGN' } });
    const result: any = await this.flutterwave.request('/payments', 'POST', { tx_ref: txRef, amount, currency: 'NGN', customer: { email }, customizations: { title: 'Nedu Wallet', description: 'Wallet funding' } });
    const checkoutUrl = result?.data?.link;
    if (!checkoutUrl) throw new ServiceUnavailableException('Flutterwave did not return a checkout link.');
    await this.prisma.paymentTransaction.update({ where: { txRef }, data: { checkoutUrl, status: 'PROCESSING' } });
    return { reference: txRef, checkoutUrl, status: 'PROCESSING' };
  }
  async createDynamicVirtualAccount(userId: string, amount: number, email: string, firstName: string, lastName: string) {
    if (!Number.isFinite(amount) || amount <= 0) throw new BadRequestException('A positive amount is required for a dynamic virtual account.');
    const txRef = 'NEDU-VA-' + randomUUID();
    await this.prisma.paymentTransaction.create({ data: { userId, txRef, kind: 'VIRTUAL_ACCOUNT', amount, currency: 'NGN' } });
    const result: any = await this.flutterwave.request('/virtual-account-numbers', 'POST', { email, amount, currency: 'NGN', tx_ref: txRef, firstname: firstName, lastname: lastName, narration: 'Nedu Wallet funding' });
    const data = result?.data;
    if (!data?.account_number || !data?.bank_name) throw new ServiceUnavailableException('Flutterwave did not return virtual account details.');
    await this.prisma.virtualAccount.create({ data: { userId, txRef, providerRef: data.flw_ref, accountNumber: data.account_number, bankName: data.bank_name, expiresAt: data.expiry_date && data.expiry_date !== 'N/A' ? new Date(data.expiry_date) : null } });
    return { reference: txRef, accountNumber: data.account_number, bankName: data.bank_name, expiresAt: data.expiry_date ?? null };
  }
  async createTransfer() { throw new ServiceUnavailableException('Transfers are disabled until withdrawal reservations, beneficiary verification, risk limits, fraud controls, reconciliation and regulatory approval are implemented.'); }
}

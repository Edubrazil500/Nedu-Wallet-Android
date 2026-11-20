import { ConflictException, Injectable, UnauthorizedException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { ConfigService } from '@nestjs/config';
import * as bcrypt from 'bcryptjs';
import { PrismaService } from '../prisma/prisma.module';
import { LoginDto, RegisterDto } from './auth.dto';
@Injectable()
export class AuthService {
  constructor(private prisma: PrismaService, private jwt: JwtService, private config: ConfigService) {}
  async register(dto: RegisterDto) {
    const email = dto.email.trim().toLowerCase();
    if (await this.prisma.user.findUnique({ where: { email } })) throw new ConflictException('An account with this email already exists.');
    const passwordHash = await bcrypt.hash(dto.password, Number(this.config.get('BCRYPT_ROUNDS', 12)));
    const user = await this.prisma.$transaction(async tx => {
      const created = await tx.user.create({ data: { email, passwordHash, firstName: dto.firstName, lastName: dto.lastName, phone: dto.phone } });
      const wallet = await tx.wallet.create({ data: { userId: created.id, currency: 'NGN' } });
      await tx.ledgerAccount.create({ data: { walletId: wallet.id, code: 'wallet:' + wallet.id, type: 'USER_WALLET', currency: 'NGN' } });
      return created;
    });
    return this.issueToken(user.id, user.email);
  }
  async login(dto: LoginDto) {
    const user = await this.prisma.user.findUnique({ where: { email: dto.email.trim().toLowerCase() } });
    if (!user || !user.isActive || !(await bcrypt.compare(dto.password, user.passwordHash))) throw new UnauthorizedException('Invalid email or password.');
    return this.issueToken(user.id, user.email);
  }
  private async issueToken(userId: string, email: string) {
    return { accessToken: await this.jwt.signAsync({ sub: userId, email }), tokenType: 'Bearer', expiresIn: this.config.get('JWT_ACCESS_TTL', '15m'), user: { id: userId, email } };
  }
}

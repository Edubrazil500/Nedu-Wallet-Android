import { Controller, Get, Req, UnauthorizedException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { ConfigService } from '@nestjs/config';
import { WalletService } from './wallet.service';
@Controller('wallet')
export class WalletController {
  constructor(private wallets: WalletService, private jwt: JwtService, private config: ConfigService) {}
  @Get('me') async getMyWallet(@Req() req: any) {
    const header = req.headers.authorization || '';
    if (!header.startsWith('Bearer ')) throw new UnauthorizedException();
    try {
      const payload = await this.jwt.verifyAsync(header.slice(7), { secret: this.config.getOrThrow('JWT_ACCESS_SECRET') });
      return this.wallets.getWallet(payload.sub);
    } catch { throw new UnauthorizedException(); }
  }
}

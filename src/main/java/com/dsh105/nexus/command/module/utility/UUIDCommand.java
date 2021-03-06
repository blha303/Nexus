/*
 * This file is part of Nexus.
 *
 * Nexus is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Nexus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Nexus.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.dsh105.nexus.command.module.utility;

import com.dsh105.nexus.command.Command;
import com.dsh105.nexus.command.CommandModule;
import com.dsh105.nexus.command.CommandPerformEvent;

import java.util.UUID;

@Command(command = "uuid",
        needsChannel = false,
        help = "Validate a UUID.",
        extendedHelp = {
                "{b}{p}{c} <uuid>{/b} - Tests if input is a valid UUID."
        })
public class UUIDCommand extends CommandModule {

    @Override
    public boolean onCommand(CommandPerformEvent event) {
        String[] args = event.getArgs();

        if (args.length != 1) {
            return false;
        }

        try {
            UUID u = UUID.fromString(args[0]);
            event.respondWithPing("{0} is a valid UUID!", args[0]);
        } catch (Exception ex) {
            event.respondWithPing("{0} is not a valid UUID!", args[0]);
        }

        return true;
    }
}
